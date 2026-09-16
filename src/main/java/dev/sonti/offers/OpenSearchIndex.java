package dev.sonti.offers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed-operation, bounded local-only REST adapter. Never physically deletes a tombstone. */
public final class OpenSearchIndex implements AutoCloseable, ShadowRebuild.Target {
    private final URI endpoint;
    private final String index;
    private final JsonMapper json;
    private final HttpClient http;

    public OpenSearchIndex(String endpoint, String index, JsonMapper json) {
        URI uri = URI.create(endpoint);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                || index == null || !index.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("Explicit loopback index endpoint and safe index name required.");
        }
        this.endpoint = uri;
        this.index = index;
        this.json = json;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    public void create() {
        try (var resource = OpenSearchIndex.class.getResourceAsStream("/search/mapping.json")) {
            var mapping = json.readTree(resource);
            request("PUT", "/" + index + "-v1", Map.of("settings", mapping.get("settings"),
                    "mappings", mapping.get("mappings"), "aliases", Map.of(index, Map.of("is_write_index", true))), false);
        } catch (java.io.IOException failed) { throw new IllegalStateException("Cannot load index mapping.", failed); }
    }

    public boolean project(Offer offer) {
        String key = offer.tenantId() + ":" + offer.merchantId() + ":" + offer.offerId();
        return request("PUT", "/" + index + "/_doc/" + key + "?version=" + offer.version()
                + "&version_type=external&require_alias=true", offer, true) != null;
    }

    public List<Offer> candidates(String tenant, String query, int count) {
        Input.identifier(tenant);
        if (query == null || query.isBlank() || query.length() > 200 || count < 1 || count > 250) {
            throw new IllegalArgumentException("Invalid search bounds.");
        }
        var body = Map.of("size", count, "track_total_hits", false, "timeout", "2s",
                "query", Map.of("bool", Map.of("must", List.of(Map.of("match", Map.of("title", query))),
                        "filter", List.of(Map.of("term", Map.of("tenantId", tenant)),
                                Map.of("term", Map.of("deleted", false))))));
        var response = request("POST", "/" + index + "/_search?allow_partial_search_results=false", body, false);
        if (response.path("timed_out").asBoolean() || response.path("_shards").path("failed").asInt() != 0
                || !response.path("hits").path("hits").isArray()) throw unavailable();
        var offers = new ArrayList<Offer>();
        for (var hit : response.path("hits").path("hits")) {
            Offer offer;
            try { offer = json.treeToValue(hit.get("_source"), Offer.class); }
            catch (RuntimeException invalid) { throw unavailable(); }
            if (offer == null || !tenant.equals(offer.tenantId()) || offers.size() >= count) throw unavailable();
            offers.add(offer);
        }
        return List.copyOf(offers);
    }

    void refresh() { request("POST", "/" + index + "/_refresh", Map.of(), false); }

    @Override public void ensure(UUID job) {
        if (!index.equals("offers-build-" + job)) throw new IllegalArgumentException("Generated shadow alias required.");
        String concrete = index + "-v1";
        Reply existing = send("GET", "/" + concrete, null, "application/json");
        if (existing.status() == 404) {
            try (var resource = OpenSearchIndex.class.getResourceAsStream("/search/mapping.json")) {
                var mapping = json.readTree(resource);
                var properties = (tools.jackson.databind.node.ObjectNode) mapping.get("mappings");
                properties.putObject("_meta").put("rebuildJob", job.toString()).put("schema", "offers-snapshot-v1");
                var created = send("PUT", "/" + concrete, json.writeValueAsString(Map.of("settings", mapping.get("settings"),
                        "mappings", properties, "aliases", Map.of(index, Map.of("is_write_index", true)))), "application/json");
                if (created.status() != 200 && !(created.status() == 400 && "resource_already_exists_exception".equals(
                        created.body().path("error").path("type").asString()))) throw unavailable();
            } catch (java.io.IOException failure) { throw unavailable(); }
            existing = send("GET", "/" + concrete, null, "application/json");
        }
        if (existing.status() != 200) throw unavailable();
        var definition = existing.body().path(concrete);
        var aliases = definition.path("aliases");
        var meta = definition.path("mappings").path("_meta");
        if (existing.body().size() != 1 || !job.toString().equals(meta.path("rebuildJob").asString())
                || !"offers-snapshot-v1".equals(meta.path("schema").asString()) || aliases.size() != 1
                || !aliases.path(index).path("is_write_index").asBoolean()) {
            throw new ShadowRebuild.InvalidTarget("OWNERSHIP_MISMATCH");
        }
    }

    @Override public void project(List<Offer> offers) {
        requireShadowPage(offers);
        var body = new StringBuilder();
        for (var offer : offers) {
            body.append(json.writeValueAsString(Map.of("index", Map.of("_id", key(offer), "version", offer.version(),
                    "version_type", "external")))).append('\n');
            body.append(json.writeValueAsString(offer)).append('\n');
        }
        Reply response = send("POST", "/" + index + "/_bulk?require_alias=true", body.toString(), "application/x-ndjson");
        var items = response.body().path("items");
        if (response.status() != 200 || !items.isArray() || items.size() != offers.size()) throw unavailable();
        for (int i = 0; i < offers.size(); i++) {
            var item = items.get(i).path("index");
            if (!key(offers.get(i)).equals(item.path("_id").asString())) throw unavailable();
            int status = item.path("status").asInt();
            boolean replay = status == 409 && "version_conflict_engine_exception".equals(item.path("error").path("type").asString());
            if (status != 200 && status != 201 && !replay) throw unavailable();
        }
    }

    @Override public boolean matches(List<Offer> offers) {
        requireShadowPage(offers);
        var result = request("POST", "/" + index + "/_mget?realtime=true",
                Map.of("ids", offers.stream().map(OpenSearchIndex::key).toList()), false).path("docs");
        if (!result.isArray() || result.size() != offers.size()) throw unavailable();
        for (int i = 0; i < offers.size(); i++) {
            var doc = result.get(i);
            if (doc.has("error")) throw unavailable();
            Offer expected = offers.get(i);
            if (!doc.path("found").asBoolean() || !key(expected).equals(doc.path("_id").asString())
                    || doc.path("_version").asLong() != expected.version()) return false;
            try { if (!expected.equals(json.treeToValue(doc.get("_source"), Offer.class))) return false; }
            catch (RuntimeException malformed) { return false; }
        }
        return true;
    }

    @Override public void freeze() {
        requireShadow();
        var reply = request("PUT", "/" + index + "-v1/_settings", Map.of("index.blocks.write", true), false);
        if (!reply.path("acknowledged").asBoolean()) throw unavailable();
    }

    @Override public long count() {
        requireShadow();
        refresh();
        var result = request("POST", "/" + index + "/_count", Map.of("query", Map.of("match_all", Map.of())), false);
        if (result.path("_shards").path("failed").asInt() != 0 || !result.path("count").isIntegralNumber()) throw unavailable();
        return result.path("count").asLong();
    }

    private void requireShadowPage(List<Offer> offers) {
        requireShadow();
        if (offers.isEmpty() || offers.size() > RebuildStore.PAGE_SIZE) throw new IllegalArgumentException("Invalid rebuild page.");
    }
    private void requireShadow() {
        if (!index.startsWith("offers-build-") || !index.substring(13).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("Generated shadow alias required.");
        }
    }
    private static String key(Offer offer) { return offer.tenantId() + ":" + offer.merchantId() + ":" + offer.offerId(); }

    private JsonNode request(String method, String path, Object body, boolean versionConflictAllowed) {
        Reply response = send(method, path, json.writeValueAsString(body), "application/json");
        var parsed = response.body();
        if (response.status() == 409 && versionConflictAllowed
                && "version_conflict_engine_exception".equals(parsed.path("error").path("type").asString())) return null;
        if (response.status() < 200 || response.status() >= 300) throw unavailable();
        return parsed;
    }

    private record Reply(int status, JsonNode body) {}
    private Reply send(String method, String path, String body, String contentType) {
        try {
            var req = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", contentType)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
            var response = http.send(req, ignored -> new LimitedBody());
            var parsed = json.readTree(response.body());
            if (parsed == null) throw unavailable();
            return new Reply(response.statusCode(), parsed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception failed) { throw unavailable(); }
    }

    private static DomainException unavailable() { return new DomainException(503, "Search temporarily unavailable."); }
    @Override public void close() { http.close(); }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private int bytes;
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                bytes += buffer.remaining();
                if (bytes > 1024 * 1024) {
                    subscription.cancel();
                    delegate.onError(new IllegalStateException("Index response limit exceeded."));
                    return;
                }
            }
            delegate.onNext(buffers);
        }
        public void onError(Throwable failure) { delegate.onError(failure); }
        public void onComplete() { delegate.onComplete(); }
    }

}
