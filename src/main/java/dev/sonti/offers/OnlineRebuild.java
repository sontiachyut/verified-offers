package dev.sonti.offers;

import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Explicit bounded coordinator; no automatic rollback of an externally switched alias. */
final class OnlineRebuild {
    enum Result { PROGRESSED, WAITING, ACTIVE, ABORTED, RETRY, BUSY_OR_TERMINAL }
    private final OnlineRebuildStore store;
    private final KafkaWindow kafka;
    private final JsonMapper json;
    OnlineRebuild(OnlineRebuildStore store, KafkaWindow kafka, JsonMapper json) {
        this.store = store; this.kafka = kafka; this.json = json;
    }
    OnlineRebuildStore.Run create(String endpoint, String alias) {
        outsideTransaction();
        if (alias.startsWith("offers-build-")) throw new IllegalArgumentException("Private shadow aliases cannot be live handoff sources.");
        try (var live = new OpenSearchIndex(endpoint, alias, json)) {
            String old = live.liveTarget();
            var boundary = kafka.capture(); // MUST precede the PostgreSQL snapshot.
            return store.create(alias, old, endpoint, boundary);
        }
    }
    Result step(UUID id) {
        outsideTransaction();
        store.get(id);
        var maybe = store.claim(id);
        if (maybe.isEmpty()) return Result.BUSY_OR_TERMINAL;
        var claim = maybe.get(); var run = claim.run();
        String failure = null;
        try (var live = new OpenSearchIndex(run.endpoint(), run.alias(), json)) {
            switch (run.state()) {
                case "CAPTURED" -> {
                    if (!live.liveTarget().equals(run.oldIndex())) throw new DomainException(409, "Live alias changed.");
                    store.pause(claim);
                }
                case "PAUSED" -> { store.requirePause(run); store.seal(claim, kafka.capture()); }
                case "REPLAYING" -> {
                    store.requirePause(run);
                    var cursor = store.cursors(id).stream().filter(c -> c.next() < c.end()).findFirst();
                    if (cursor.isPresent()) {
                        var c = cursor.get();
                        var records = kafka.read(run.end(), c.partition(), c.next(), c.end());
                        if (records.isEmpty()) return Result.WAITING;
                        store.replay(claim, c, records);
                    } else {
                        KafkaWindow.validate(run.end(), kafka.capture());
                        store.materialize(claim);
                    }
                }
                case "BUILDING" -> {
                    store.requirePause(run);
                    var candidate = store.snapshots.get(run.candidate());
                    ShadowRebuild.Result progress;
                    try (var target = new OpenSearchIndex(run.endpoint(), candidate.shadowAlias(), json)) {
                        progress = new ShadowRebuild(store.snapshots, target).step(candidate.id());
                    }
                    String state = store.snapshots.get(candidate.id()).state();
                    if (state.equals("INVALID")) { failure = "CANDIDATE_INVALID"; return Result.RETRY; }
                    if (state.equals("SNAPSHOT_VALIDATED")) store.switching(claim);
                    else if (progress == ShadowRebuild.Result.RETRY || progress == ShadowRebuild.Result.LEASE_LOST) {
                        failure = "STEP_FAILED"; return Result.RETRY;
                    } else if (progress == ShadowRebuild.Result.BUSY_OR_TERMINAL) return Result.WAITING;
                }
                case "SWITCHING" -> {
                    store.requirePause(run);
                    KafkaWindow.validate(run.end(), kafka.capture());
                    var candidate = store.snapshots.get(run.candidate());
                    if (!candidate.state().equals("SNAPSHOT_VALIDATED")) throw new DomainException(409, "Candidate not validated.");
                    try (var target = new OpenSearchIndex(run.endpoint(), candidate.shadowAlias(), json)) {
                        live.promote(run.oldIndex(), target, candidate.id());
                    }
                    store.finish(claim);
                    return Result.ACTIVE;
                }
                default -> throw new IllegalStateException("Unexpected coordinator phase.");
            }
            return Result.PROGRESSED;
        } catch (KafkaWindow.InvalidBoundary invalid) {
            failure = "WINDOW_INVALID"; return Result.RETRY;
        } catch (IllegalArgumentException invalid) {
            failure = "EVENT_INVALID"; return Result.RETRY;
        } catch (RuntimeException failed) {
            failure = "STEP_FAILED"; return Result.RETRY;
        } finally { store.release(claim, failure); }
    }
    Result abort(UUID id) {
        outsideTransaction();
        var run = store.get(id);
        if (run.state().equals("ABORTED")) return Result.ABORTED;
        if (run.state().equals("ACTIVE") || run.state().equals("SWITCHING")) {
            throw new DomainException(409, "An active or uncertain handoff cannot be rolled back. Reconcile forward.");
        }
        var maybe = store.claim(id);
        if (maybe.isEmpty()) return Result.BUSY_OR_TERMINAL;
        var claim = maybe.get();
        try (var live = new OpenSearchIndex(run.endpoint(), run.alias(), json)) {
            if (!live.liveTarget().equals(run.oldIndex())) throw new DomainException(409, "Live alias changed; abort refused.");
            store.abort(claim);
            return Result.ABORTED;
        } finally { store.release(claim, null); }
    }
    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Coordinator network calls require no ambient transaction.");
    }
}
