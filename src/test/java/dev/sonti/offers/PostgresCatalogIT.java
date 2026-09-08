package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PostgresCatalogIT extends PostgresFixture {
    private PostgresCatalog catalog;
    private final Instant sourceTime = Instant.parse("2026-01-01T00:00:00.123456789Z");
    @BeforeEach void reset() {
        sql.execute("TRUNCATE offer_key, offer_head, offer_version, outbox CASCADE");
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), JsonMapper.builder().build());
    }
    private Offer offer(long version, long price) {
        return new Offer("tenant", "merchant", "item", version, "Headphones", price, "USD", 5, sourceTime, false);
    }
    @Test void migrationReplayAndMicrosecondPrecision() {
        var first = catalog.ingest(offer(1, 1000));
        assertThat(catalog.ingest(offer(1, 1000))).isEqualTo(first);
        assertThat(first.sourceUpdatedAt()).isEqualTo(sourceTime.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT length(payload_hash) FROM offer_version", Integer.class)).isEqualTo(64);
    }
    @Test void conflictingAndStaleVersionsDoNotWriteHistory() {
        catalog.ingest(offer(2, 1000));
        assertThatThrownBy(() -> catalog.ingest(offer(2, 999))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> catalog.ingest(offer(1, 1000))).isInstanceOf(DomainException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class)).isEqualTo(1);
    }
    @Test void immutableHistoryAndCurrentHeadAreDifferent() {
        catalog.ingest(offer(1, 1000));
        catalog.ingest(offer(2, 2000));
        assertThat(catalog.get("tenant", "merchant", "item").priceMinor()).isEqualTo(2000);
        assertThat(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> sql.update("UPDATE offer_version SET payload_hash='bad'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM offer_version WHERE version=1")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void outboxFailureRollsBackEntireIngestion() {
        sql.execute("ALTER TABLE outbox ADD CONSTRAINT test_reject CHECK (event_type = 'impossible') NOT VALID");
        try {
            assertThatThrownBy(() -> catalog.ingest(offer(1, 1000))).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(sql.queryForObject("SELECT count(*) FROM offer_head", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM offer_key", Integer.class)).isZero();
        } finally { sql.execute("ALTER TABLE outbox DROP CONSTRAINT test_reject"); }
    }
    @Test void independentAdaptersPersistAndConvergeOnConcurrentFirstWrite() throws Exception {
        try (var secondPool = newPool(); var executor = Executors.newFixedThreadPool(16)) {
            var second = new PostgresCatalog(new JdbcTemplate(secondPool),
                    new TransactionTemplate(new JdbcTransactionManager(secondPool)),
                    Clock.systemUTC(), Duration.ofMinutes(5), JsonMapper.builder().build());
            var tasks = new ArrayList<Callable<Offer>>();
            for (int i = 0; i < 100; i++) {
                var target = i % 2 == 0 ? catalog : second;
                tasks.add(() -> target.ingest(offer(1, 1000)));
            }
            for (var result : executor.invokeAll(tasks)) assertThat(result.get().version()).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
            assertThat(second.get("tenant", "merchant", "item")).isEqualTo(offer(1, 1000));
        }
    }
    @Test void concurrentDifferentVersionsNeverRegressHead() throws Exception {
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 1; i <= 100; i++) {
                final int version = i;
                tasks.add(() -> { try { catalog.ingest(offer(version, version)); }
                    catch (DomainException stale) { assertThat(stale.status()).isEqualTo(409); } return null; });
            }
            for (var result : executor.invokeAll(tasks)) result.get();
        }
        assertThat(catalog.get("tenant", "merchant", "item").version()).isEqualTo(100);
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class))
                .isEqualTo(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class));
    }
    @Test void verificationRespectsFreshnessDeletionAndTenantIdentity() {
        catalog.ingest(offer(1, 1000));
        var claim = new Catalog.Claim("tenant", "merchant", "item", 1000, "USD");
        assertThat(catalog.verify(claim).outcome()).isEqualTo(Catalog.Outcome.STALE);
        catalog.ingest(new Offer("tenant", "merchant", "item", 2, "Title", 1000, "USD", 5, sourceTime, true));
        assertThat(catalog.verify(claim).outcome()).isEqualTo(Catalog.Outcome.DELETED);
        assertThat(catalog.verify(new Catalog.Claim("other", "merchant", "item", 1000, "USD")).outcome())
                .isEqualTo(Catalog.Outcome.NOT_FOUND);
    }
}
