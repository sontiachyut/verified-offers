package dev.sonti.offers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class FeedStoreIT extends PostgresFixture {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Instant sourceTime = Instant.parse("2026-09-01T12:00:00Z");
    private FeedStore store;
    private PostgresCatalog catalog;
    @BeforeEach void reset() {
        sql.execute("TRUNCATE feed_job,offer_key,offer_head,offer_version,outbox CASCADE");
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
        store = new FeedStore(sql, new JdbcTransactionManager(pool), json, catalog);
    }
    private Offer offer(String id, long version) {
        return new Offer("demo", "merchant", id, version, "Keyboard", 999 + version, "USD", 5, sourceTime, false);
    }
    private FeedInput.Upload upload(String content) throws Exception {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return new FeedInput(Clock.systemUTC()).read(new ByteArrayInputStream(bytes), -1, FeedInput.sha256(bytes), "demo", "merchant");
    }
    private FeedInput.Upload offers(int count) throws Exception {
        var lines = new ArrayList<String>();
        for (int i = 0; i < count; i++) lines.add(json.writeValueAsString(offer("item-" + i, 1)));
        return upload(String.join("\n", lines));
    }
    private FeedStore.Job submit(FeedInput.Upload upload) { return store.submit("demo", "merchant", "feed", "synthetic", upload).job(); }
    private FeedStore.Job get(UUID id) { return store.get("demo", "merchant", id); }
    private void ready(UUID id) { sql.update("UPDATE feed_job SET available_at=statement_timestamp() WHERE job_id=?", id); }

    @Test void concurrentAdmissionIsIdempotentAndChangedRequestOrScopeIsRejected() throws Exception {
        var upload = offers(2);
        var ids = new HashSet<UUID>(); int created = 0;
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<FeedStore.Accepted>>();
            for (int i = 0; i < 20; i++) tasks.add(() -> store.submit("demo", "merchant", "feed", "synthetic", upload));
            for (var future : executor.invokeAll(tasks)) { var result = future.get(); ids.add(result.job().id()); if (result.created()) created++; }
        }
        assertThat(ids).hasSize(1); assertThat(created).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM feed_row", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> store.submit("demo", "merchant", "feed", "changed", upload)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> submit(offers(3))).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> store.get("other", "merchant", ids.iterator().next()))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(404));
    }
    @Test void inputOrderProducesAppliedReplayedConflictAndSanitizedRowResults() throws Exception {
        String first = json.writeValueAsString(offer("item", 1));
        var job = submit(upload(String.join("\n", first, first, json.writeValueAsString(offer("item", 2)), first, "not-json-secret", json.writeValueAsString(offer("last", 1)))));
        assertThat(job.processed()).isEqualTo(1); assertThat(job.rejected()).isEqualTo(1);
        assertThat(new FeedWorker(store).step()).isEqualTo(FeedWorker.Result.COMPLETED);
        job = get(job.id());
        assertThat(job.state()).isEqualTo("COMPLETED_WITH_ERRORS"); assertThat(job.processed()).isEqualTo(6);
        assertThat(job.applied()).isEqualTo(3); assertThat(job.replayed()).isEqualTo(1); assertThat(job.rejected()).isEqualTo(2);
        assertThat(store.rows("demo", "merchant", job.id(), 0, 100).rows().stream().map(FeedStore.Row::state))
                .containsExactly("APPLIED", "REPLAYED", "APPLIED", "REJECTED", "REJECTED", "APPLIED");
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(3);
        assertThat(sql.queryForObject("SELECT payload IS NULL FROM feed_row WHERE row_number=5", Boolean.class)).isTrue();
        assertThat(catalog.get("demo", "merchant", "item").sourceUpdatedAt()).isEqualTo(sourceTime);
        assertThat(store.submit("demo", "merchant", "feed", "synthetic", upload(String.join("\n", first, first,
                json.writeValueAsString(offer("item", 2)), first, "not-json-secret", json.writeValueAsString(offer("last", 1))))).created()).isFalse();
    }
    @Test void outboxAndReceiptFailuresRollbackCatalogAndProgressTogether() throws Exception {
        var job = submit(offers(1)); var worker = new FeedWorker(store);
        for (String table : List.of("outbox", "feed_row")) {
            String condition = table.equals("outbox") ? "event_type='impossible'" : "state<>'APPLIED'";
            sql.execute("ALTER TABLE " + table + " ADD CONSTRAINT feed_fixture_fail CHECK (" + condition + ") NOT VALID");
            try {
                assertThat(worker.step()).isEqualTo(FeedWorker.Result.RETRY);
                assertThat(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class)).isZero();
                assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isZero();
                assertThat(get(job.id()).processed()).isZero();
                assertThat(store.rows("demo", "merchant", job.id(), 0, 10).rows().getFirst().state()).isEqualTo("PENDING");
            } finally { sql.execute("ALTER TABLE " + table + " DROP CONSTRAINT feed_fixture_fail"); ready(job.id()); }
        }
        assertThat(worker.step()).isEqualTo(FeedWorker.Result.COMPLETED);
        assertThat(get(job.id()).applied()).isEqualTo(1);
    }
    @Test void expiredOwnerCannotCheckpointOrReleaseReplacementLease() throws Exception {
        var job = submit(offers(2)); var old = store.claim().orElseThrow();
        assertThat(store.processOne(old)).isTrue();
        sql.update("UPDATE feed_job SET lease_until=statement_timestamp()-interval '1 second' WHERE job_id=?", job.id());
        var replacement = store.claim().orElseThrow();
        assertThatThrownBy(() -> store.processOne(old)).isInstanceOf(FeedStore.LeaseLost.class);
        store.release(old); store.fail(old);
        assertThat(get(job.id()).state()).isEqualTo("RUNNING");
        assertThat(store.processOne(replacement)).isFalse();
        assertThat(get(job.id()).applied()).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(2);
    }
    @Test void competingWorkersAndMultipleBatchesDoNotDuplicateRows() throws Exception {
        var job = submit(offers(53));
        assertThat(new FeedWorker(store).step()).isEqualTo(FeedWorker.Result.PROGRESSED);
        assertThat(get(job.id()).processed()).isEqualTo(25);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> { var worker = new FeedWorker(store); for (int j = 0; j < 5; j++) worker.step(); return null; });
            for (var task : executor.invokeAll(tasks)) task.get();
        }
        assertThat(get(job.id()).state()).isEqualTo("COMPLETED");
        assertThat(get(job.id()).applied()).isEqualTo(53);
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(53);
    }
    @Test void fiveFailuresPauseThenAuditedRetryResumesWithoutLosingRows() throws Exception {
        var job = submit(offers(2));
        for (int i = 1; i <= 5; i++) {
            var claim = store.claim().orElseThrow(); store.fail(claim);
            assertThat(get(job.id()).failures()).isEqualTo(i);
            if (i < 5) { assertThat(store.claim()).isEmpty(); ready(job.id()); }
        }
        assertThat(get(job.id()).state()).isEqualTo("PAUSED");
        assertThat(store.claim()).isEmpty();
        assertThat(store.control("demo", "merchant", job.id(), "RETRY", "dependency-restored").state()).isEqualTo("QUEUED");
        assertThat(new FeedWorker(store).step()).isEqualTo(FeedWorker.Result.COMPLETED);
        assertThat(store.actions("demo", "merchant", job.id())).hasSize(1);
        assertThat(get(job.id()).failures()).isZero();
    }
    @Test void cancellationKeepsCommittedWorkAndFencesWorker() throws Exception {
        var job = submit(offers(3)); var claim = store.claim().orElseThrow(); store.processOne(claim);
        var cancelled = store.control("demo", "merchant", job.id(), "CANCEL", "stop-fixture");
        assertThat(cancelled.state()).isEqualTo("CANCELLED"); assertThat(cancelled.applied()).isEqualTo(1);
        assertThat(cancelled.cancelled()).isEqualTo(2); assertThat(cancelled.processed()).isEqualTo(3);
        assertThatThrownBy(() -> store.processOne(claim)).isInstanceOf(FeedStore.LeaseLost.class);
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.control("demo", "merchant", job.id(), "RETRY", "no-undo")).isInstanceOf(DomainException.class);
    }
    @Test void provenanceAndTerminalReceiptsAreImmutableWithBoundedPages() throws Exception {
        var job = submit(offers(4)); new FeedWorker(store).step();
        var first = store.rows("demo", "merchant", job.id(), 0, 2);
        assertThat(first.rows()).hasSize(2); assertThat(first.nextAfter()).isEqualTo(2);
        assertThat(store.rows("demo", "merchant", job.id(), first.nextAfter(), 2).nextAfter()).isNull();
        assertThatThrownBy(() -> sql.update("UPDATE feed_job SET source_label='changed' WHERE job_id=?", job.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE feed_row SET state='PENDING' WHERE job_id=?", job.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM feed_row WHERE job_id=?", job.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> store.rows("other", "merchant", job.id(), 0, 10)).isInstanceOf(DomainException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> store.rows("demo", "merchant", job.id(), 0, 101));
        assertThatIllegalStateException().isThrownBy(() -> catalog.ingestInTransaction(offer("unsafe", 1)));
    }
    @Test void allInvalidFeedCompletesWithoutWorkerAndRetentionCapDoesNotBreakReplay() throws Exception {
        var input = upload("not-json");
        for (int i = 0; i < 100; i++) {
            assertThat(store.submit("demo", "merchant", "key-" + i, "synthetic", input).job().state()).isEqualTo("COMPLETED_WITH_ERRORS");
        }
        assertThat(new FeedWorker(store).step()).isEqualTo(FeedWorker.Result.IDLE);
        assertThat(store.submit("demo", "merchant", "key-0", "synthetic", input).created()).isFalse();
        assertThatThrownBy(() -> store.submit("demo", "merchant", "over-cap", "synthetic", input))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.status()).isEqualTo(429));
        var page = store.list("demo", "merchant", null, 60);
        assertThat(page.jobs()).hasSize(60);
        assertThat(store.list("demo", "merchant", page.nextAfter(), 60).jobs()).hasSize(40);
    }
}
