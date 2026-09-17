package dev.sonti.offers;

import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ReconciliationProcessIT extends PostgresFixture {
    static final GenericContainer<?> search = SearchTestContainer.create();
    private final JsonMapper json = JsonMapper.builder().build();
    @BeforeAll static void startSearch() { search.start(); }
    @AfterAll static void stopSearch() { search.stop(); }
    @Test void persistedIntentResumesAcrossProcessesAndCannotResurrectTombstone() throws Exception {
        String alias = "repair-" + UUID.randomUUID();
        var catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
        var first = catalog.ingest(new Offer("t", "m", "o", 1, "Keyboard", 100, "USD", 1, Instant.now(), false));
        sql.update("INSERT INTO index_quarantine(topic,partition_id,record_offset,reason,payload_sha256) VALUES ('offers.v1',0,1,'INVALID_ENVELOPE',?)", "b".repeat(64));
        try (var index = new OpenSearchIndex(endpoint(), alias, json)) {
            index.create();
            var intent = command(alias, "--reconcile=prepare", "--topic=offers.v1", "--partition=0", "--offset=1",
                    "--tenant=t", "--merchant=m", "--offer=o", "--operator=synthetic-owner", "--reason=reviewed-producer");
            String id = intent.path("id").asString();
            var tombstone = catalog.ingest(new Offer("t", "m", "o", 2, "Keyboard", 100, "USD", 0, Instant.now(), true));
            index.project(tombstone);
            assertThat(command(alias, "--reconcile=status", "--id=" + id).path("attempts").asInt()).isZero();
            assertThat(command(alias, "--reconcile=step", "--id=" + id).path("completedAt").isNull()).isFalse();
            assertThat(command(alias, "--reconcile=step", "--id=" + id).path("attempts").asInt()).isEqualTo(1);
            index.refresh();
            assertThat(index.candidates("t", "keyboard", 10)).isEmpty();
            try (var http = java.net.http.HttpClient.newHttpClient()) {
                var response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(endpoint() + "/" + alias + "/_doc/t:m:o"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(json.readTree(response.body()).path("_version").asLong()).isEqualTo(2);
                assertThat(json.readTree(response.body()).path("_source").path("deleted").asBoolean()).isTrue();
            }
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class)).isZero();
        }
    }
    private String endpoint() { return "http://127.0.0.1:" + search.getMappedPort(9200); }
    private JsonNode command(String alias, String... args) throws Exception {
        var command = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-jar", "target/verified-offers-0.1.0-SNAPSHOT.jar"));
        command.addAll(List.of(args)); var builder = new ProcessBuilder(command);
        builder.environment().putAll(Map.of("APP_DATABASE_URL", postgres.getJdbcUrl(), "APP_DATABASE_USER", postgres.getUsername(),
                "APP_DATABASE_PASSWORD", postgres.getPassword(), "OFFERS_SEARCH_ENDPOINT", endpoint(), "OFFERS_SEARCH_INDEX", alias,
                "OFFERS_PUBLISHER_ENABLED", "true", "OFFERS_INDEXER_ENABLED", "true", "OFFERS_FEEDS_WORKER_ENABLED", "true"));
        var process = builder.redirectErrorStream(true).start();
        try {
            if (!process.waitFor(25, TimeUnit.SECONDS)) throw new AssertionError("Command timeout");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            return json.readTree(output.lines().filter(line -> line.startsWith("{")).findFirst().orElseThrow());
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
    }
}
