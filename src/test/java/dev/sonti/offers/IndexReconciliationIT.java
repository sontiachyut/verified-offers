package dev.sonti.offers;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class IndexReconciliationIT extends PostgresFixture {
    private final JsonMapper json = JsonMapper.builder().build();
    private IndexReconciliation store;
    private PostgresCatalog catalog;
    private final IndexReconciliation.Position position = new IndexReconciliation.Position("offers.v1", 0, 42);
    @BeforeEach void reset() {
        sql.execute("TRUNCATE offer_key,offer_version,offer_head,outbox,index_quarantine,index_reconciliation CASCADE");
        store = new IndexReconciliation(sql, transactions, json);
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
        sql.update("INSERT INTO index_quarantine(topic,partition_id,record_offset,reason,payload_sha256) VALUES ('offers.v1',0,42,'INVALID_ENVELOPE',?)", "a".repeat(64));
        catalog.ingest(offer(1, false));
    }
    private Offer offer(long version, boolean deleted) {
        return new Offer("t", "m", "o", version, "Keyboard", 100, "USD", 1, Instant.parse("2026-01-01T00:00:00Z"), deleted);
    }
    private IndexReconciliation.Intent prepare() { return store.prepare(position, "t", "m", "o", "test-operator", "producer-repaired"); }
    @Test void intentIsIdempotentAndBoundToOriginalImmutableSnapshot() {
        var intent = prepare(); catalog.ingest(offer(2, true));
        assertThat(prepare()).isEqualTo(intent);
        assertThatThrownBy(() -> store.prepare(position, "t", "m", "o", "another", "producer-repaired")).isInstanceOf(DomainException.class);
        var projected = new ArrayList<Offer>();
        var done = store.step(intent.id(), projected::add, () -> {});
        assertThat(projected).containsExactly(offer(1, false));
        assertThat(done.completedAt()).isNotNull();
        store.step(intent.id(), projected::add, () -> {});
        assertThat(projected).hasSize(1);
        assertThat(catalog.get("t", "m", "o").version()).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT count(*) FROM index_quarantine", Integer.class)).isEqualTo(1);
    }
    @Test void missingSourceDoesNotWriteIntentAndProvenanceCannotBeChanged() {
        assertThatThrownBy(() -> store.prepare(position, "other", "m", "o", "test", "test")).isInstanceOf(DomainException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM index_reconciliation", Integer.class)).isZero();
        prepare();
        assertThatThrownBy(() -> sql.update("DELETE FROM index_quarantine")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE index_reconciliation SET reason='changed'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM index_reconciliation")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void failedProjectionRemainsPendingAndHasFiniteBudget() {
        var intent = prepare();
        for (int i = 0; i < 8; i++) assertThatThrownBy(() -> store.step(intent.id(), o -> { throw new IllegalStateException("outage"); }, () -> {}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.get(intent.id()).attempts()).isEqualTo(8);
        assertThat(store.get(intent.id()).completedAt()).isNull();
        assertThatThrownBy(() -> store.step(intent.id(), o -> { throw new AssertionError("Must not project"); }, () -> {})).isInstanceOf(DomainException.class);
    }
    @Test void acknowledgementGapReplaysAndIndexGatePreventsAttempt() {
        var intent = prepare(); var projected = new ArrayList<Offer>();
        assertThatThrownBy(() -> store.step(intent.id(), projected::add, () -> { throw new DomainException(409, "paused"); })).isInstanceOf(DomainException.class);
        assertThat(store.get(intent.id()).attempts()).isZero();
        assertThatThrownBy(() -> store.step(intent.id(), o -> { projected.add(o); throw new SimulatedCrash(); }, () -> {})).isInstanceOf(SimulatedCrash.class);
        var restarted = new IndexReconciliation(sql, transactions, json);
        restarted.step(intent.id(), projected::add, () -> {});
        assertThat(projected).containsExactly(offer(1, false), offer(1, false));
        assertThat(restarted.get(intent.id()).attempts()).isEqualTo(2);
    }
    static class SimulatedCrash extends Error {}
}
