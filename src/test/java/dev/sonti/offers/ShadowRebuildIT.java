package dev.sonti.offers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ShadowRebuildIT extends PostgresFixture {
    static final GenericContainer<?> search = SearchTestContainer.create();
    private final JsonMapper json = JsonMapper.builder().build();
    private RebuildStore store;
    private PostgresCatalog catalog;
    @BeforeAll static void startIndex() { search.start(); }
    @AfterAll static void stopIndex() { search.stop(); }
    @BeforeEach void reset() {
        sql.execute("TRUNCATE index_rebuild,offer_key,offer_head,offer_version,outbox,outbox_replay CASCADE");
        store = new RebuildStore(sql, new JdbcTransactionManager(pool), json);
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
    }
    private String endpoint() { return "http://127.0.0.1:" + search.getMappedPort(9200); }
    private OpenSearchIndex index(RebuildStore.Job job) { return new OpenSearchIndex(endpoint(), job.shadowAlias(), json); }
    private Offer offer(String id, long version, boolean deleted) {
        return new Offer("demo", "merchant", id, version, "Mechanical keyboard", 999, "USD", 4, Instant.now(), deleted);
    }
    private RebuildStore.Job finish(UUID id, ShadowRebuild runner) {
        for (int step = 0; step < 20; step++) {
            var result = runner.step(id);
            if (result == ShadowRebuild.Result.SNAPSHOT_VALIDATED || result == ShadowRebuild.Result.INVALID) return store.get(id);
            assertThat(result).isEqualTo(ShadowRebuild.Result.PROGRESSED);
        }
        throw new AssertionError("Bounded fixture did not finish");
    }
    private JsonNode raw(String method, String path, Object body, int status) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create(endpoint() + path)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var reply = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(reply.statusCode()).isEqualTo(status);
            return json.readTree(reply.body());
        }
    }

    @Test void pagedShadowSnapshotPreservesTombstonesAndNeverSwitchesLiveAlias() throws Exception {
        Offer original = catalog.ingest(offer("item-000", 1, false));
        String liveAlias = "live-" + UUID.randomUUID();
        try (var live = new OpenSearchIndex(endpoint(), liveAlias, json)) {
            live.create(); live.project(original); live.refresh();
            for (int i = 1; i < 103; i++) catalog.ingest(offer("item-" + String.format("%03d", i), 1, i % 2 == 0));
            var job = store.create();
            try (var shadow = index(job)) {
                var runner = new ShadowRebuild(store, shadow);
                assertThat(runner.step(job.id())).isEqualTo(ShadowRebuild.Result.PROGRESSED);
                assertThat(store.get(job.id()).projected()).isEqualTo(100);
                // Source ingestion continues between batches; the snapshot must not drift.
                catalog.ingest(offer("item-000", 2, true));
                catalog.ingest(offer("new", 1, false));
                var done = finish(job.id(), runner);
                assertThat(done.state()).isEqualTo("SNAPSHOT_VALIDATED");
                assertThat(done.total()).isEqualTo(103);
                assertThat(done.projected()).isEqualTo(103);
                assertThat(done.validated()).isEqualTo(103);
                assertThat(shadow.matches(List.of(original))).isTrue();
                assertThat(shadow.count()).isEqualTo(103); // Includes deletion snapshots.
                assertThat(live.candidates("demo", "keyboard", 10)).containsExactly(original);
                assertThat(raw("GET", "/_alias/" + liveAlias, null, 200).has(liveAlias + "-v1")).isTrue();
                assertThat(runner.step(job.id())).isEqualTo(ShadowRebuild.Result.BUSY_OR_TERMINAL);
                assertThatThrownBy(() -> shadow.project(offer("new-write", 1, false))).isInstanceOf(DomainException.class);
            }
        }
    }
    @Test void crashAfterBulkAcknowledgementReplaysWithoutAdvancingAnotherLease() {
        Offer original = catalog.ingest(offer("item", 1, false));
        var job = store.create();
        try (var shadow = index(job)) {
            var crashing = new ShadowRebuild.Target() {
                public void ensure(UUID id) { shadow.ensure(id); }
                public void project(List<Offer> offers) { shadow.project(offers); throw new SimulatedCrash(); }
                public void freeze() { shadow.freeze(); }
                public boolean matches(List<Offer> offers) { return shadow.matches(offers); }
                public long count() { return shadow.count(); }
            };
            assertThatThrownBy(() -> new ShadowRebuild(store, crashing).step(job.id())).isInstanceOf(SimulatedCrash.class);
            assertThat(shadow.matches(List.of(original))).isTrue();
            assertThat(store.get(job.id()).projected()).isZero();
            assertThat(store.get(job.id()).leaseUntil()).isNotNull();
            var resumed = new ShadowRebuild(new RebuildStore(sql, new JdbcTransactionManager(pool), json), shadow);
            assertThat(resumed.step(job.id())).isEqualTo(ShadowRebuild.Result.BUSY_OR_TERMINAL);
            sql.update("UPDATE index_rebuild SET lease_until=statement_timestamp()-interval '1 second' WHERE job_id=?", job.id());
            assertThat(finish(job.id(), resumed).state()).isEqualTo("SNAPSHOT_VALIDATED");
            assertThat(shadow.count()).isEqualTo(1);
        }
    }
    @Test void missingChangedWrongVersionAndExtraDocumentsInvalidateJob() throws Exception {
        Offer original = catalog.ingest(offer("item", 1, false));
        for (String fault : List.of("missing", "changed", "version", "extra")) {
            var job = store.create();
            try (var shadow = index(job)) {
                var runner = new ShadowRebuild(store, shadow);
                assertThat(runner.step(job.id())).isEqualTo(ShadowRebuild.Result.PROGRESSED);
                String doc = "/" + job.shadowAlias() + "-v1/_doc/demo:merchant:item";
                switch (fault) {
                    case "missing" -> raw("DELETE", doc, null, 200);
                    case "changed" -> {
                        var changed = new Offer("demo", "merchant", "item", 1, "Changed title", 999, "USD", 4, original.sourceUpdatedAt(), false);
                        raw("PUT", doc + "?version=1&version_type=external_gte", changed, 200);
                    }
                    case "version" -> raw("PUT", doc + "?version=2&version_type=external", original, 200);
                    case "extra" -> shadow.project(offer("extra", 1, false));
                    default -> throw new AssertionError();
                }
                assertThat(finish(job.id(), runner).state()).as(fault).isEqualTo("INVALID");
                assertThat(store.get(job.id()).lastError()).isEqualTo(fault.equals("extra") ? "COUNT_MISMATCH" : "CONTENT_MISMATCH");
            }
        }
    }
    @Test void refusesExistingForeignIndexAndAdditionalAliases() throws Exception {
        var foreign = store.create();
        try (var shadow = index(foreign)) {
            shadow.create(); // No rebuild ownership metadata: must not adopt it.
            assertThat(new ShadowRebuild(store, shadow).step(foreign.id())).isEqualTo(ShadowRebuild.Result.INVALID);
            assertThat(store.get(foreign.id()).lastError()).isEqualTo("OWNERSHIP_MISMATCH");
        }
        var extraAlias = store.create();
        try (var shadow = index(extraAlias)) {
            shadow.ensure(extraAlias.id());
            raw("POST", "/_aliases", Map.of("actions", List.of(Map.of("add", Map.of(
                    "index", extraAlias.shadowAlias() + "-v1", "alias", "accidental-" + UUID.randomUUID())))), 200);
            assertThat(new ShadowRebuild(store, shadow).step(extraAlias.id())).isEqualTo(ShadowRebuild.Result.INVALID);
        }
    }
    @Test void emptySnapshotValidatesAndIndexOutageRemainsResumable() {
        var job = store.create();
        try (var shadow = index(job)) {
            var runner = new ShadowRebuild(store, shadow);
            search.getDockerClient().pauseContainerCmd(search.getContainerId()).exec();
            try { assertThat(runner.step(job.id())).isEqualTo(ShadowRebuild.Result.RETRY); }
            finally { search.getDockerClient().unpauseContainerCmd(search.getContainerId()).exec(); }
            assertThat(store.get(job.id()).projected()).isZero();
            assertThat(store.get(job.id()).lastError()).isEqualTo("STEP_FAILED");
            assertThat(finish(job.id(), runner).state()).isEqualTo("SNAPSHOT_VALIDATED");
            assertThat(shadow.count()).isZero();
        }
    }

    @Test void packagedOperatorResumesAcrossProcessesWithoutStartingWebOrWorkers() throws Exception {
        Offer original = catalog.ingest(offer("item", 1, false));
        var created = command(0, "--rebuild=create", "--max-offers=10");
        UUID id = UUID.fromString(created.path("job").path("id").asString());
        assertThat(created.path("promotable").asBoolean()).isFalse();
        catalog.ingest(offer("item", 2, true));
        assertThat(command(0, "--rebuild=step", "--job=" + id, "--endpoint=" + endpoint())
                .path("job").path("state").asString()).isEqualTo("VALIDATING");
        assertThat(command(0, "--rebuild=step", "--job=" + id, "--endpoint=" + endpoint())
                .path("job").path("validated").asLong()).isEqualTo(1);
        assertThat(command(0, "--rebuild=step", "--job=" + id, "--endpoint=" + endpoint())
                .path("result").asString()).isEqualTo("SNAPSHOT_VALIDATED");
        assertThat(command(0, "--rebuild=status", "--job=" + id).path("job").path("state").asString())
                .isEqualTo("SNAPSHOT_VALIDATED");
        try (var shadow = index(store.get(id))) { assertThat(shadow.matches(List.of(original))).isTrue(); }
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class)).isZero();
    }
    @Test void packagedOperatorRejectsUnsafeOptionsAndOversizeCapture() throws Exception {
        catalog.ingest(offer("one", 1, false));
        catalog.ingest(offer("two", 1, false));
        command(2, "--rebuild=create", "--max-offers=1");
        command(2, "--rebuild=create", "--offers.publisher.enabled=true");
        command(2, "--rebuild=step", "--job=" + UUID.randomUUID(), "--endpoint=http://example.com:9200");
        assertThat(sql.queryForObject("SELECT count(*) FROM index_rebuild", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM offer_head", Integer.class)).isEqualTo(2);
    }
    private JsonNode command(int expectedExit, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-jar",
                "target/verified-offers-0.1.0-SNAPSHOT.jar"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("APP_DATABASE_URL", postgres.getJdbcUrl());
        builder.environment().put("APP_DATABASE_USER", postgres.getUsername());
        builder.environment().put("APP_DATABASE_PASSWORD", postgres.getPassword());
        // Forced operator settings must override inherited flags, even without Kafka configuration.
        builder.environment().put("OFFERS_PUBLISHER_ENABLED", "true");
        builder.environment().put("OFFERS_INDEXER_ENABLED", "true");
        builder.environment().put("OFFERS_SEARCH_ENABLED", "true");
        var process = builder.start();
        try {
            assertThat(process.waitFor(25, java.util.concurrent.TimeUnit.SECONDS)).as("one-shot process exits itself").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isEqualTo(expectedExit);
            assertThat(output).doesNotContain("Tomcat", "Kafka version:", "Exception:");
            if (expectedExit != 0) {
                assertThat(output).contains("Rebuild command failed.");
                return json.readTree("{}");
            }
            return output.lines().filter(line -> line.startsWith("{")).map(json::readTree).findFirst().orElseThrow();
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); }
        }
    }
    private static final class SimulatedCrash extends Error {}
}
