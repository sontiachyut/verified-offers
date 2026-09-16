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

    String alias() { return index; }

    String liveTarget() {
        var aliases = request("GET", "/_alias/" + index, null, false);
        if (aliases.size() != 1) throw new DomainException(409, "Live alias must resolve to one write index.");
        var entry = aliases.properties().iterator().next();
        var definition = entry.getValue().path("aliases").path(index);
        if (definition.size() != 1 || !definition.path("is_write_index").asBoolean()) {
            throw new DomainException(409, "Filtered or implicit write aliases are not supported.");
        }
        return entry.getKey();
    }

    void promote(String expectedOld, OpenSearchIndex candidate, UUID candidateId) {
        if (!candidate.alias().equals("offers-build-" + candidateId) || !endpoint.equals(candidate.endpoint)
                || expectedOld == null || !expectedOld.matches("[a-z][a-z0-9-]{0,254}")) {
            throw new IllegalArgumentException("Invalid handoff target.");
        }
        String target = candidate.alias() + "-v1";
        String current = liveTarget();
        if (!current.equals(expectedOld) && !current.equals(target)) throw new DomainException(409, "Live alias changed.");
        if (current.equals(expectedOld)) candidate.ensure(candidateId);
        var unblocked = candidate.request("PUT", "/" + target + "/_settings", Map.of("index.blocks.write", false), false);
        if (!unblocked.path("acknowledged").asBoolean()) throw unavailable();
        if (current.equals(expectedOld)) {
            var reply = request("POST", "/_aliases", Map.of("actions", List.of(
                    Map.of("remove", Map.of("index", expectedOld, "alias", index, "must_exist", true)),
                    Map.of("add", Map.of("index", target, "alias", index, "is_write_index", true)))), false);
            if (!reply.path("acknowledged").asBoolean()) throw unavailable();
        }
        if (!target.equals(liveTarget())) throw unavailable();
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

    record Hit(Offer offer, List<Object> sort) {}

    String openPointInTime() {
        var response = request("POST", "/" + index + "/_search/point_in_time?keep_alive=2m&allow_partial_pit_creation=false", Map.of(), false);
        String id = response.path("pit_id").asString();
        if (id == null || id.isBlank() || id.length() > 16384 || response.path("_shards").path("failed").asInt() != 0) throw unavailable();
        return id;
    }

    List<Hit> page(String pit, String tenant, String query, int count, List<Object> after) {
        Input.identifier(tenant);
        if (pit == null || pit.isBlank() || pit.length() > 16384 || query == null || query.isBlank()
                || query.length() > 200 || count < 1 || count > 250 || (after != null && after.size() != 3)) {
            throw new IllegalArgumentException("Invalid PIT search bounds.");
        }
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("size", count); body.put("track_total_hits", false); body.put("timeout", "2s");
        body.put("pit", Map.of("id", pit)); // Never renew: abandoned contexts have a fixed resource lifetime.
        body.put("sort", List.of(Map.of("_score", "desc"), Map.of("merchantId", "asc"), Map.of("offerId", "asc")));
        body.put("query", Map.of("bool", Map.of("must", List.of(Map.of("match", Map.of("title", query))),
                "filter", List.of(Map.of("term", Map.of("tenantId", tenant)), Map.of("term", Map.of("deleted", false))))));
        if (after != null) body.put("search_after", after);
        var response = request("POST", "/_search?allow_partial_search_results=false", body, false);
        if (response.path("timed_out").asBoolean() || response.path("_shards").path("failed").asInt() != 0
                || !response.path("hits").path("hits").isArray()) throw unavailable();
        var hits = new ArrayList<Hit>();
        for (var hit : response.path("hits").path("hits")) {
            try {
                Offer offer = json.treeToValue(hit.get("_source"), Offer.class);
                var sort = hit.path("sort");
                if (offer == null || !tenant.equals(offer.tenantId()) || hits.size() >= count || !sort.isArray()
                        || sort.size() != 3 || !sort.get(0).isNumber() || !Double.isFinite(sort.get(0).asDouble())
                        || !offer.merchantId().equals(sort.get(1).asString()) || !offer.offerId().equals(sort.get(2).asString())) throw unavailable();
                hits.add(new Hit(offer, List.of(sort.get(0).asDouble(), offer.merchantId(), offer.offerId())));
            } catch (RuntimeException invalid) { throw unavailable(); }
        }
        return List.copyOf(hits);
    }

    void closePointsInTime(List<String> ids) {
        if (ids.isEmpty()) return;
        if (ids.size() > 128 || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 16384)) {
            throw new IllegalArgumentException("Invalid PIT close bounds.");
        }
        request("DELETE", "/_search/point_in_time", Map.of("pit_id", ids), false);
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
        Reply response = send(method, path, body == null ? null : json.writeValueAsString(body), "application/json");
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
