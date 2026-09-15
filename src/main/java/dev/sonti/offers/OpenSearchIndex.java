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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed-operation, bounded local-only REST adapter. Never physically deletes a tombstone. */
public final class OpenSearchIndex implements AutoCloseable {
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

    private JsonNode request(String method, String path, Object body, boolean versionConflictAllowed) {
        try {
            var req = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var response = http.send(req, ignored -> new LimitedBody());
            var parsed = json.readTree(response.body());
            if (response.statusCode() == 409 && versionConflictAllowed
                    && "version_conflict_engine_exception".equals(parsed.path("error").path("type").asString())) return null;
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw unavailable();
            return parsed;
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
