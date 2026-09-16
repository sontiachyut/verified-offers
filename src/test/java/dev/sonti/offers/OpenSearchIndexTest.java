package dev.sonti.offers;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenSearchIndexTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void rejectsNonLoopbackEndpointsAndUnsafeIndexNames() {
        for (String uri : new String[]{"https://127.0.0.1:9200", "http://example.com:9200", "http://user:pass@127.0.0.1:9200",
                "http://127.0.0.1:9200/path", "http://127.0.0.1:9200?query", "http://127.0.0.1:9200#fragment", "http://127.0.0.1"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new OpenSearchIndex(uri, "offers", json));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> new OpenSearchIndex("http://127.0.0.1:9200", "../bad", json));
    }
    @Test void rejectsPartialMalformedOversizedAndUnexpectedConflictResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        var body = new java.util.concurrent.atomic.AtomicReference<>("{}");
        server.createContext("/", exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try (var index = new OpenSearchIndex("http://127.0.0.1:" + server.getAddress().getPort(), "offers", json)) {
            for (String response : new String[]{"{}", "not-json", " ".repeat(1024 * 1024 + 1),
                    "{\"timed_out\":true,\"hits\":{\"hits\":[]}}",
                    "{\"_shards\":{\"failed\":1},\"hits\":{\"hits\":[]}}"}) {
                body.set(response);
                assertThatThrownBy(() -> index.candidates("demo", "keyboard", 10))
                        .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.status()).isEqualTo(503));
            }
            status.set(409);
            body.set("{\"error\":{\"type\":\"unrelated_conflict\"}}");
            var offer = new Offer("demo", "merchant", "keyboard", 1, "Keyboard", 999, "USD", 4, Instant.now(), false);
            assertThatThrownBy(() -> index.project(offer)).isInstanceOf(DomainException.class);
        } finally { server.stop(0); }
    }

    @Test void shadowBulkChecksEveryItemAndAcceptsOnlyVersionConflictReplays() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var body = new java.util.concurrent.atomic.AtomicReference<>("{}");
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (var index = new OpenSearchIndex("http://127.0.0.1:" + server.getAddress().getPort(),
                "offers-build-" + java.util.UUID.randomUUID(), json)) {
            var offers = java.util.List.of(
                    new Offer("demo", "merchant", "one", 1, "Keyboard", 999, "USD", 4, Instant.now(), false),
                    new Offer("demo", "merchant", "two", 1, "Keyboard", 999, "USD", 4, Instant.now(), false));
            body.set("""
                    {"errors":true,"items":[
                      {"index":{"_id":"demo:merchant:one","status":201}},
                      {"index":{"_id":"demo:merchant:two","status":400,"error":{"type":"mapper_parsing_exception","reason":"private-fixture"}}}]}
                    """);
            assertThatThrownBy(() -> index.project(offers)).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.getMessage()).isEqualTo("Search temporarily unavailable."));
            body.set("""
                    {"errors":true,"items":[
                      {"index":{"_id":"demo:merchant:one","status":409,"error":{"type":"version_conflict_engine_exception"}}},
                      {"index":{"_id":"demo:merchant:two","status":201}}]}
                    """);
            assertThatCode(() -> index.project(offers)).doesNotThrowAnyException();
            body.set(body.get().replace("version_conflict_engine_exception", "unrelated_conflict"));
            assertThatThrownBy(() -> index.project(offers)).isInstanceOf(DomainException.class);
        } finally { server.stop(0); }
    }
}
