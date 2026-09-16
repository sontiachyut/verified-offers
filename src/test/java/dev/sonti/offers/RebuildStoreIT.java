package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class RebuildStoreIT extends PostgresFixture {
    private final JsonMapper json = JsonMapper.builder().build();
    private RebuildStore store;
    private PostgresCatalog catalog;
    @BeforeEach void reset() {
        sql.execute("TRUNCATE index_rebuild,offer_key,offer_head,offer_version,outbox,outbox_replay CASCADE");
        store = new RebuildStore(sql, new JdbcTransactionManager(pool), json);
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
    }
    private Offer offer(String id, long version, boolean deleted) {
        return new Offer("demo", "merchant", id, version, "Keyboard", 999, "USD", 4, Instant.now(), deleted);
    }
    @Test void frozenSnapshotSurvivesNewVersionsNewKeysAndTombstones() {
        Offer first = catalog.ingest(offer("first", 1, false));
        Offer deleted = catalog.ingest(offer("deleted", 3, true));
        var job = store.create();
        catalog.ingest(offer("first", 2, true));
        catalog.ingest(offer("new", 1, false));
        var claim = store.claim(job.id()).orElseThrow();
        assertThat(job.total()).isEqualTo(2);
        assertThat(store.page(claim)).extracting(RebuildStore.Item::offer).containsExactly(deleted, first);
        assertThatThrownBy(() -> sql.update("UPDATE index_rebuild_item SET version=2 WHERE job_id=?", job.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void oversizeCaptureRollsBackJobAndItemsAtomically() {
        catalog.ingest(offer("one", 1, false));
        catalog.ingest(offer("two", 1, false));
        var limited = new RebuildStore(sql, new JdbcTransactionManager(pool), json, 1);
        assertThatThrownBy(limited::create).isInstanceOf(DomainException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM index_rebuild", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM index_rebuild_item", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM offer_head", Integer.class)).isEqualTo(2);
    }
    @Test void concurrentClaimsHaveOneWinnerAndExpiredOwnerCannotCheckpointOrRelease() throws Exception {
        var job = store.create();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new java.util.concurrent.CyclicBarrier(2);
            var tasks = executor.<java.util.Optional<RebuildStore.Claim>>invokeAll(List.of(
                    () -> { barrier.await(); return store.claim(job.id()); },
                    () -> { barrier.await(); return store.claim(job.id()); }));
            var results = List.of(tasks.get(0).get(), tasks.get(1).get());
            assertThat(results.stream().filter(java.util.Optional::isPresent).count()).isEqualTo(1);
        }
        sql.update("UPDATE index_rebuild SET lease_until=statement_timestamp()-interval '1 second' WHERE job_id=?", job.id());
        var old = store.claim(job.id()).orElseThrow();
        sql.update("UPDATE index_rebuild SET lease_until=statement_timestamp()-interval '1 second' WHERE job_id=?", job.id());
        var replacement = store.claim(job.id()).orElseThrow();
        assertThat(store.checkpoint(old, 0)).isFalse();
        assertThat(store.fail(old, "STEP_FAILED", false)).isFalse();
        assertThat(store.checkpoint(replacement, 0)).isTrue();
        assertThat(store.get(job.id()).state()).isEqualTo("VALIDATING");
    }
    @Test void pagesAreBoundedAndResumeAfterPersistedOrdinal() {
        for (int i = 0; i < 103; i++) catalog.ingest(offer("item-" + String.format("%03d", i), 1, i % 2 == 0));
        var job = store.create();
        var first = store.claim(job.id()).orElseThrow();
        assertThat(store.page(first)).hasSize(100);
        assertThat(store.checkpoint(first, 100)).isTrue();
        var restarted = new RebuildStore(sql, new JdbcTransactionManager(pool), json);
        var second = restarted.claim(job.id()).orElseThrow();
        assertThat(restarted.page(second)).extracting(RebuildStore.Item::ordinal).containsExactly(101L, 102L, 103L);
    }
    @Test void jobBudgetAndUnknownJobsAreExplicit() {
        for (int i = 0; i < 10; i++) store.create();
        assertThatThrownBy(store::create).isInstanceOf(DomainException.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM index_rebuild", Integer.class)).isEqualTo(10);
        assertThatThrownBy(() -> store.get(UUID.randomUUID())).isInstanceOf(DomainException.class);
    }
    @Test void failureLeavesCheckpointAndAmbientTransactionIsRejected() {
        var job = store.create();
        var target = new ShadowRebuild.Target() {
            public void ensure(UUID id) { throw new IllegalStateException("Injected failure"); }
            public void project(List<Offer> offers) { throw new AssertionError(); }
            public void freeze() { throw new AssertionError(); }
            public boolean matches(List<Offer> offers) { throw new AssertionError(); }
            public long count() { throw new AssertionError(); }
        };
        var rebuild = new ShadowRebuild(store, target);
        assertThat(rebuild.step(job.id())).isEqualTo(ShadowRebuild.Result.RETRY);
        assertThat(store.get(job.id()).projected()).isZero();
        assertThat(store.get(job.id()).lastError()).isEqualTo("STEP_FAILED");
        assertThat(store.get(job.id()).leaseUntil()).isNull();
        assertThatThrownBy(() -> transactions.execute(status -> rebuild.step(job.id()))).isInstanceOf(IllegalStateException.class);
    }
}
