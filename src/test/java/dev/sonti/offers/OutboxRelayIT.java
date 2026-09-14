package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OutboxRelayIT extends PostgresFixture {
    private PostgresOutbox outbox;
    private PostgresCatalog catalog;

    @BeforeEach void reset() {
        sql.execute("TRUNCATE offer_key,offer_head,offer_version,outbox,outbox_replay CASCADE");
        outbox = store(sql, pool);
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), JsonMapper.builder().build());
    }

    private PostgresOutbox store(JdbcTemplate jdbc, javax.sql.DataSource source) {
        return new PostgresOutbox(jdbc, new JdbcTransactionManager(source));
    }

    private void ingest(String tenant, String item, long version, boolean deleted) {
        catalog.ingest(new Offer(tenant, "merchant", item, version, "Headphones", 1000, "USD", 5,
                Instant.parse("2026-01-01T00:00:00Z"), deleted));
    }

    private void expire(UUID id) {
        sql.update("UPDATE outbox SET lease_until=statement_timestamp()-interval '1 second' WHERE event_id=?", id);
    }

    @Test void crashAfterDestinationAcceptsReplaysSameEventAndPayload() {
        ingest("tenant", "item", 1, false);
        var accepted = new ArrayList<PostgresOutbox.Delivery>();
        var crashing = new OutboxRelay(outbox, event -> {
            accepted.add(event);
            throw new SimulatedCrash(); // No catch/failure cleanup: model abrupt worker death.
        });
        assertThatThrownBy(crashing::publishNext).isInstanceOf(SimulatedCrash.class);
        var first = accepted.getFirst();
        assertThat(sql.queryForObject("SELECT published_at IS NULL FROM outbox", Boolean.class)).isTrue();
        assertThat(outbox.claim()).isEmpty();
        expire(first.eventId());

        var restarted = new OutboxRelay(store(sql, pool), accepted::add);
        assertThat(restarted.publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
        assertThat(accepted).hasSize(2);
        assertThat(accepted.getLast().eventId()).isEqualTo(first.eventId());
        assertThat(accepted.getLast().payload()).isEqualTo(first.payload());
        assertThat(accepted.getLast().key()).isEqualTo(first.key());
        assertThat(accepted.getLast().leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(accepted.getLast().attempt()).isEqualTo(2);
        assertThat(outbox.claim()).isEmpty();
        assertThat(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class)).isEqualTo(1);
    }

    @Test void twoPoolsClaimConcurrentWorkWithoutDuplicateOwnership() throws Exception {
        for (int i = 0; i < 40; i++) ingest("tenant", "item-" + i, 1, false);
        try (var secondPool = newPool(); var executor = Executors.newFixedThreadPool(8)) {
            var second = store(new JdbcTemplate(secondPool), secondPool);
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Callable<PostgresOutbox.Delivery>>();
            for (int i = 0; i < 40; i++) {
                var target = i % 2 == 0 ? outbox : second;
                tasks.add(() -> { start.await(); return target.claim().orElseThrow(); });
            }
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            var identities = new HashSet<UUID>();
            for (var future : futures) assertThat(identities.add(future.get(10, TimeUnit.SECONDS).eventId())).isTrue();
            assertThat(identities).hasSize(40);
            assertThat(outbox.claim()).isEmpty();
        }
    }

    @Test void expiredAndSupersededWorkersCannotAcknowledgeOrChangeRetryState() {
        ingest("tenant", "item", 1, false);
        var old = outbox.claim().orElseThrow();
        expire(old.eventId());
        assertThat(outbox.markPublished(old)).isFalse();
        assertThat(outbox.recordFailure(old, PostgresOutbox.Failure.SEND_FAILED)).isFalse();
        var current = outbox.claim().orElseThrow();
        assertThat(outbox.markPublished(old)).isFalse();
        assertThat(outbox.recordFailure(old, PostgresOutbox.Failure.SEND_FAILED)).isFalse();
        assertThat(outbox.markPublished(current)).isTrue();
        assertThat(outbox.markPublished(current)).isFalse();
    }

    @Test void sendFailureBacksOffAndStoresOnlyFixedErrorCode() {
        ingest("tenant", "item", 1, false);
        var relay = new OutboxRelay(outbox, event -> { throw new IllegalStateException("private endpoint credential"); });
        assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.RETRY_OR_QUARANTINED);
        var row = sql.queryForMap("SELECT last_error, lease_token, published_at, attempts, "
                + "EXTRACT(EPOCH FROM (next_attempt_at-statement_timestamp())) AS delay FROM outbox");
        assertThat(row.get("last_error")).isEqualTo("SEND_FAILED");
        assertThat(row.get("lease_token")).isNull();
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(((Number) row.get("delay")).doubleValue()).isBetween(0.0, 1.0);
        assertThat(outbox.claim()).isEmpty();
        sql.update("UPDATE outbox SET next_attempt_at=statement_timestamp()-interval '1 second'");
        assertThat(new OutboxRelay(outbox, event -> {}).publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
    }

    @Test void repeatedFailuresQuarantineAndExplicitReplayIsAudited() {
        ingest("tenant", "item", 1, false);
        UUID id = null;
        for (int i = 1; i <= 8; i++) {
            var event = outbox.claim().orElseThrow();
            id = event.eventId();
            assertThat(event.attempt()).isEqualTo(i);
            assertThat(outbox.recordFailure(event, PostgresOutbox.Failure.SEND_FAILED)).isTrue();
            sql.update("UPDATE outbox SET next_attempt_at=statement_timestamp()-interval '1 second'");
        }
        assertThat(outbox.claim()).isEmpty();
        assertThat(sql.queryForObject("SELECT quarantined_at IS NOT NULL FROM outbox", Boolean.class)).isTrue();
        assertThat(outbox.requeueQuarantined(id, "Synthetic destination recovered")).isTrue();
        assertThat(outbox.requeueQuarantined(id, "Duplicate operator request")).isFalse();
        assertThat(sql.queryForObject("SELECT previous_attempts FROM outbox_replay", Integer.class)).isEqualTo(8);
        assertThat(sql.queryForObject("SELECT reason FROM outbox_replay", String.class)).isEqualTo("Synthetic destination recovered");
        var replay = outbox.claim().orElseThrow();
        assertThat(replay.eventId()).isEqualTo(id);
        assertThat(replay.attempt()).isEqualTo(1);
        assertThat(outbox.markPublished(replay)).isTrue();
    }

    @Test void exhaustedAbandonedClaimIsQuarantinedWithoutBlockingOtherEvents() {
        ingest("tenant", "stuck", 1, false);
        var stuck = outbox.claim().orElseThrow();
        sql.update("UPDATE outbox SET attempts=8 WHERE event_id=?", stuck.eventId());
        expire(stuck.eventId());
        ingest("tenant", "next", 1, false);
        assertThat(outbox.claim().orElseThrow().eventId()).isNotEqualTo(stuck.eventId());
        assertThat(sql.queryForObject("SELECT last_error FROM outbox WHERE event_id=?", String.class,
                stuck.eventId())).isEqualTo("ATTEMPTS_EXHAUSTED");
    }

    @Test void tombstonesAndTenantKeysPreservePersistedEnvelope() {
        ingest("first", "item", 1, false);
        ingest("first", "item", 2, true);
        ingest("second", "item", 1, false);
        var delivered = new ArrayList<PostgresOutbox.Delivery>();
        var relay = new OutboxRelay(outbox, delivered::add);
        for (int i = 0; i < 3; i++) assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
        assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.IDLE);
        assertThat(delivered).extracting(PostgresOutbox.Delivery::key)
                .containsExactlyInAnyOrder("first:merchant:item", "first:merchant:item", "second:merchant:item");
        var json = JsonMapper.builder().build();
        var tombstone = delivered.stream().map(event -> json.readTree(event.payload()))
                .filter(event -> event.get("eventType").asString().equals("OfferDeleted")).findFirst().orElseThrow();
        assertThat(tombstone.get("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(tombstone.get("payload").get("deleted").asBoolean()).isTrue();
        for (var event : delivered) assertThat(event.payload()).isEqualTo(sql.queryForObject(
                "SELECT payload::text FROM outbox WHERE event_id=?", String.class, event.eventId()));
    }

    @Test void destinationIoRunsOutsideTransactionAndDoesNotHoldRowLock() {
        ingest("tenant", "item", 1, false);
        var relay = new OutboxRelay(outbox, event -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("SET LOCAL lock_timeout='250ms'");
                statement.executeQuery("SELECT event_id FROM outbox FOR UPDATE NOWAIT").close();
                connection.rollback();
            }
        });
        assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
        assertThatThrownBy(() -> transactions.execute(status -> relay.publishNext())).isInstanceOf(IllegalStateException.class);
    }

    @Test void interruptedSendReleasesClaimAndPreservesInterruptFlag() {
        ingest("tenant", "item", 1, false);
        var relay = new OutboxRelay(outbox, event -> { throw new InterruptedException(); });
        try {
            assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.RETRY_OR_QUARANTINED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
        assertThat(sql.queryForObject("SELECT last_error FROM outbox", String.class)).isEqualTo("SEND_INTERRUPTED");
    }

    @Test void acknowledgementDatabaseFailureLeavesLeaseForRecovery() {
        ingest("tenant", "item", 1, false);
        sql.execute("ALTER TABLE outbox ADD CONSTRAINT test_no_ack CHECK (published_at IS NULL)");
        var accepted = new ArrayList<PostgresOutbox.Delivery>();
        try {
            assertThatThrownBy(() -> new OutboxRelay(outbox, accepted::add).publishNext())
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(accepted).hasSize(1);
            assertThat(sql.queryForObject("SELECT lease_token IS NOT NULL AND last_error IS NULL FROM outbox", Boolean.class)).isTrue();
        } finally { sql.execute("ALTER TABLE outbox DROP CONSTRAINT test_no_ack"); }
        expire(accepted.getFirst().eventId());
        assertThat(new OutboxRelay(outbox, accepted::add).publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
        assertThat(accepted.getLast().eventId()).isEqualTo(accepted.getFirst().eventId());
    }

    @Test void replayAuditFailureRollsBackReplay() {
        ingest("tenant", "item", 1, false);
        var event = outbox.claim().orElseThrow();
        sql.update("UPDATE outbox SET attempts=8 WHERE event_id=?", event.eventId());
        expire(event.eventId());
        outbox.claim();
        sql.execute("ALTER TABLE outbox_replay ADD CONSTRAINT test_no_replay CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(() -> outbox.requeueQuarantined(event.eventId(), "Test failure"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(outbox.claim()).isEmpty();
            assertThat(sql.queryForObject("SELECT attempts FROM outbox", Integer.class)).isEqualTo(8);
        } finally { sql.execute("ALTER TABLE outbox_replay DROP CONSTRAINT test_no_replay"); }
    }

    @Test void migrationUpgradesExistingP2EventsWithoutChangingIdentityOrPublication() throws Exception {
        sql.execute("CREATE SCHEMA legacy_upgrade");
        try (var connection = pool.getConnection()) {
            connection.setSchema("legacy_upgrade");
            var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            var legacySql = new JdbcTemplate(source);
            var manager = new JdbcTransactionManager(source);
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas("legacy_upgrade")
                    .defaultSchema("legacy_upgrade").target("1").load().migrate();
            var legacyCatalog = new PostgresCatalog(legacySql,
                    new org.springframework.transaction.support.TransactionTemplate(manager), Clock.systemUTC(),
                    Duration.ofMinutes(5), JsonMapper.builder().build());
            legacyCatalog.ingest(new Offer("tenant", "merchant", "old", 1, "Headphones", 1000, "USD", 5,
                    Instant.parse("2026-01-01T00:00:00Z"), false));
            legacySql.update("UPDATE outbox SET published_at=statement_timestamp()");
            legacyCatalog.ingest(new Offer("tenant", "merchant", "pending", 1, "Headphones", 1000, "USD", 5,
                    Instant.parse("2026-01-01T00:00:00Z"), true));
            var before = legacySql.queryForList("SELECT event_id,payload::text,published_at FROM outbox ORDER BY aggregate_id");
            org.flywaydb.core.Flyway.configure().dataSource(source).schemas("legacy_upgrade")
                    .defaultSchema("legacy_upgrade").load().migrate();
            assertThat(legacySql.queryForList("SELECT event_id,payload::text,published_at FROM outbox ORDER BY aggregate_id"))
                    .isEqualTo(before);
            var upgraded = new PostgresOutbox(legacySql, manager);
            var event = upgraded.claim().orElseThrow();
            assertThat(event.key()).isEqualTo("tenant:merchant:pending");
            assertThat(upgraded.markPublished(event)).isTrue();
            assertThat(upgraded.claim()).isEmpty();
            connection.setSchema("public");
        }
    }

    @Test void slowAcknowledgementCannotMarkAnExpiredLeasePublished() {
        ingest("tenant", "item", 1, false);
        var relay = new OutboxRelay(outbox, event -> expire(event.eventId()));
        assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.LEASE_LOST);
        assertThat(sql.queryForObject("SELECT published_at IS NULL FROM outbox", Boolean.class)).isTrue();
        assertThat(new OutboxRelay(outbox, event -> {}).publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
    }

    private static final class SimulatedCrash extends Error {}
}
