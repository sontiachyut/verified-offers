package dev.sonti.offers;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class FeedApiIT extends PostgresFixture {
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String PATH = "/api/v1/feeds/demo/merchant";
    @BeforeEach void reset() { sql.execute("TRUNCATE feed_job,offer_key,offer_head,offer_version,outbox CASCADE"); }
    private byte[] body(int count) {
        var rows = new ArrayList<String>();
        Instant time = Instant.now();
        for (int i = 0; i < count; i++) rows.add(json.writeValueAsString(new Offer("demo", "merchant", "item-" + i, 1,
                "Mechanical keyboard", 999, "USD", 5, time, false)));
        return String.join("\n", rows).getBytes(StandardCharsets.UTF_8);
    }
    private Map<String, String> headers(byte[] body) {
        return Map.of("Content-Type", "application/x-ndjson", "Idempotency-Key", "upload-1", "X-Content-SHA256", FeedInput.sha256(body), "X-Feed-Source", "synthetic");
    }
    @Test void httpAdmissionIdempotencyScopePagesCancelAndBounds() throws Exception {
        try (var app = new RunningApplication("--offers.feeds.enabled=true")) {
            byte[] body = body(3);
            var created = app.rawRequest("POST", PATH, body, headers(body), true, 202);
            String id = created.path("job").path("id").asString();
            assertThat(created.path("job").path("state").asString()).isEqualTo("QUEUED");
            assertThat(app.rawRequest("POST", PATH, body, headers(body), false, 200).path("created").asBoolean()).isFalse();
            byte[] changed = body(4); app.rawRequest("POST", PATH, changed, headers(changed), false, 409);
            var wrongChecksum = new HashMap<>(headers(body)); wrongChecksum.put("X-Content-SHA256", "0".repeat(64));
            app.rawRequest("POST", PATH, body, wrongChecksum, false, 400);
            var encoding = new HashMap<>(headers(body)); encoding.put("Content-Encoding", "gzip");
            app.rawRequest("POST", PATH, body, encoding, false, 415);
            app.request("GET", PATH.replace("demo", "other") + "/" + id, null, 404);
            var rows = app.request("GET", PATH + "/" + id + "/rows?limit=2", null, 200);
            assertThat(rows.path("rows").size()).isEqualTo(2); assertThat(rows.path("nextAfter").asInt()).isEqualTo(2);
            assertThat(rows.toString()).doesNotContain("payload", "Mechanical keyboard", "leaseToken");
            assertThat(app.request("GET", PATH + "/" + id + "/rows?after=2&limit=2", null, 200).path("rows").size()).isEqualTo(1);
            app.request("GET", PATH + "/" + id + "/rows?limit=101", null, 400);
            assertThat(app.request("GET", PATH + "?limit=1", null, 200).path("jobs").size()).isEqualTo(1);
            var cancelled = app.request("POST", PATH + "/" + id + "/cancel", Map.of("reason", "operator-stop"), 200);
            assertThat(cancelled.path("cancelled").asInt()).isEqualTo(3);
            app.request("POST", PATH + "/" + id + "/retry", Map.of("reason", "no-undo"), 409);
            assertThat(app.request("GET", PATH + "/" + id + "/actions", null, 200).size()).isEqualTo(1);
            byte[] large = new byte[FeedInput.MAX_BYTES + 1];
            app.rawRequest("POST", PATH, large, headers(large), true, 413);
            app.rawRequest("POST", PATH, large, headers(large), false, 413);
            assertThat(sql.queryForObject("SELECT count(*) FROM feed_job", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
        }
    }
    @Test void forcedWorkerJvmStopResumesCommittedReceiptsAfterLeaseTakeover() throws Exception {
        byte[] body = body(3); String id;
        // Hold only row 2's catalog key: row 1 must commit before the worker blocks.
        try (var lock = pool.getConnection()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("INSERT INTO offer_key VALUES ('demo','merchant','item-1')")) { statement.executeUpdate(); }
            try (var app = new RunningApplication("--offers.feeds.enabled=true", "--offers.feeds.worker-enabled=true")) {
                id = app.rawRequest("POST", PATH, body, headers(body), false, 202).path("job").path("id").asString();
                UUID jobId = UUID.fromString(id);
                RunningApplication.awaitReady(() -> true, () -> sql.queryForObject("SELECT processed FROM feed_job WHERE job_id=?", Integer.class, jobId) == 1, Duration.ofSeconds(15));
                app.crash();
                assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
            }
            lock.commit();
        }
        // Advance the persisted lease only in this synthetic fixture to avoid a minute-long wall-clock test.
        sql.update("UPDATE feed_job SET lease_until=statement_timestamp()-interval '1 second' WHERE job_id=?", UUID.fromString(id));
        try (var restarted = new RunningApplication("--offers.feeds.enabled=true", "--offers.feeds.worker-enabled=true")) {
            RunningApplication.awaitReady(() -> true, () -> "COMPLETED".equals(
                    restarted.request("GET", PATH + "/" + id, null, 200).path("state").asString()), Duration.ofSeconds(15));
            assertThat(restarted.rawRequest("POST", PATH, body, headers(body), false, 200).path("created").asBoolean()).isFalse();
            assertThat(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class)).isEqualTo(3);
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(3);
            assertThat(restarted.request("GET", PATH + "/" + id + "/rows", null, 200).path("rows"))
                    .allMatch(row -> row.path("state").asString().equals("APPLIED"));
        }
    }
    @Test void packagedOperatorIsOneShotAndOverridesInheritedWorkers() throws Exception {
        String id;
        try (var app = new RunningApplication("--offers.feeds.enabled=true")) {
            byte[] body = body(28);
            id = app.rawRequest("POST", PATH, body, headers(body), false, 202).path("job").path("id").asString();
        }
        assertThat(command(0, "--feed=status", "--tenant=demo", "--merchant=merchant", "--job=" + id).path("job").path("processed").asInt()).isZero();
        assertThat(command(0, "--feed=step").path("result").asString()).isEqualTo("PROGRESSED");
        assertThat(sql.queryForObject("SELECT processed FROM feed_job WHERE job_id=?", Integer.class, UUID.fromString(id))).isEqualTo(25);
        assertThat(command(0, "--feed=step").path("result").asString()).isEqualTo("COMPLETED");
        assertThat(command(0, "--feed=step").path("result").asString()).isEqualTo("IDLE");
        command(2, "--feed=step", "--offers.feeds.worker-enabled=true");
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class)).isZero();
    }
    private JsonNode command(int expected, String... arguments) throws Exception {
        var args = new ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-jar", "target/verified-offers-0.1.0-SNAPSHOT.jar"));
        args.addAll(List.of(arguments));
        var builder = new ProcessBuilder(args).redirectErrorStream(true);
        builder.environment().putAll(Map.of("APP_DATABASE_URL", postgres.getJdbcUrl(), "APP_DATABASE_USER", postgres.getUsername(),
                "APP_DATABASE_PASSWORD", postgres.getPassword(), "OFFERS_FEEDS_ENABLED", "true", "OFFERS_FEEDS_WORKER_ENABLED", "true",
                "OFFERS_PUBLISHER_ENABLED", "true", "OFFERS_INDEXER_ENABLED", "true", "OFFERS_SEARCH_ENABLED", "true"));
        var process = builder.start();
        try {
            assertThat(process.waitFor(25, TimeUnit.SECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isEqualTo(expected);
            assertThat(output).doesNotContain("Tomcat", "Exception:", "Kafka version:");
            return expected == 0 ? output.lines().filter(line -> line.startsWith("{")).map(json::readTree).findFirst().orElseThrow() : json.readTree("{}");
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
    }
}
