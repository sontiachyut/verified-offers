package dev.sonti.offers;

import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class BackupRestoreIT extends PostgresFixture {
    @Test void logicalBackupRestoresIntoIndependentDatabaseWithHistoryReceiptsAndRecoveryState() throws Exception {
        var json = JsonMapper.builder().build(); var clock = Clock.systemUTC();
        var catalog = new PostgresCatalog(sql, transactions, clock, Duration.ofMinutes(5), json);
        var original = new Offer("restore", "merchant", "keyboard", 1, "Keyboard", 1999, "USD", 1, Instant.now(), false);
        catalog.ingest(original);
        var deleted = catalog.ingest(new Offer("restore", "merchant", "keyboard", 2, "Keyboard", 1999, "USD", 0, Instant.now(), true));
        var feeds = new FeedStore(sql, new JdbcTransactionManager(pool), json, catalog);
        byte[] body = (json.writeValueAsString(original) + "\n{broken\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));
        var upload = new FeedInput(clock).read(new java.io.ByteArrayInputStream(body), body.length, sha, "restore", "merchant");
        var job = feeds.submit("restore", "merchant", "backup-fixture", "test", upload).job();
        new FeedWorker(feeds).step(); // Old version conflict and bad row receipts are both retained.
        var outbox = new PostgresOutbox(sql, new JdbcTransactionManager(pool));
        var pendingLease = outbox.claim().orElseThrow();
        var expectedHistory = sql.queryForList("SELECT payload_hash FROM offer_version ORDER BY version", String.class);
        var expectedReceipts = feeds.rows("restore", "merchant", job.id(), 0, 100);
        long started = System.nanoTime();
        var dump = postgres.execInContainer("pg_dump", "--no-owner", "--no-privileges", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName());
        assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
        byte[] archive = dump.getStdout().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(archive).isNotEmpty();
        try (var destination = new PostgreSQLContainer(DockerImageName.parse(postgres.getDockerImageName()).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("restored")) {
            destination.start();
            destination.copyFileToContainer(Transferable.of(archive), "/tmp/offers-restore.sql");
            var restored = destination.execInContainer("psql", "-v", "ON_ERROR_STOP=1", "-U", destination.getUsername(), "-d", "restored", "-f", "/tmp/offers-restore.sql");
            assertThat(restored.getExitCode()).as(restored.getStderr()).isZero();
            var config = new HikariConfig(); config.setJdbcUrl(destination.getJdbcUrl());
            config.setUsername(destination.getUsername()); config.setPassword(destination.getPassword()); config.setMaximumPoolSize(2);
            try (var restoredPool = new HikariDataSource(config)) {
                var jdbc = new JdbcTemplate(restoredPool); var manager = new JdbcTransactionManager(restoredPool);
                var restoredCatalog = new PostgresCatalog(jdbc, new TransactionTemplate(manager), clock, Duration.ofMinutes(5), json);
                assertThat(restoredCatalog.get("restore", "merchant", "keyboard")).isEqualTo(deleted);
                assertThat(jdbc.queryForList("SELECT payload_hash FROM offer_version ORDER BY version", String.class)).isEqualTo(expectedHistory);
                var restoredFeeds = new FeedStore(jdbc, manager, json, restoredCatalog);
                assertThat(restoredFeeds.rows("restore", "merchant", job.id(), 0, 100)).isEqualTo(expectedReceipts);
                assertThat(restoredFeeds.submit("restore", "merchant", "backup-fixture", "test", upload).created()).isFalse();
                assertThatThrownBy(() -> jdbc.execute("UPDATE offer_version SET price_minor=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThatThrownBy(() -> jdbc.execute("DELETE FROM feed_row")).isInstanceOf(org.springframework.dao.DataAccessException.class);
                jdbc.update("UPDATE outbox SET lease_until=statement_timestamp()-interval '1 second' WHERE event_id=?", pendingLease.eventId());
                var restoredOutbox = new PostgresOutbox(jdbc, manager);
                var replay = restoredOutbox.claim().orElseThrow();
                assertThat(replay.eventId()).isEqualTo(pendingLease.eventId());
                assertThat(replay.payload()).isEqualTo(pendingLease.payload());
                assertThat(replay.leaseToken()).isNotEqualTo(pendingLease.leaseToken());
                assertThat(restoredOutbox.markPublished(replay)).isTrue();
                org.flywaydb.core.Flyway.configure().dataSource(restoredPool).load().validate();
            }
        }
        Files.createDirectories(Path.of("target/validation"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/validation/restore.json").toFile(), Map.of(
                "kind", "logical-pg-dump-independent-container", "historyVersions", expectedHistory.size(), "feedReceipts", expectedReceipts.rows().size(),
                "dumpBytes", archive.length, "elapsedMillisIncludingTargetStartup", (System.nanoTime() - started) / 1_000_000,
                "restoredTombstone", true, "restoredIdempotency", true, "restoredImmutableTriggers", true, "restoredLeaseReplay", true,
                "productionRpoRtoMeasured", false));
    }
}
