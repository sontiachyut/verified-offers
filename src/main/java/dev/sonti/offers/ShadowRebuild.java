package dev.sonti.offers;

import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One bounded step. A validated snapshot is never automatically promoted. */
final class ShadowRebuild {
    interface Target {
        void ensure(UUID job);
        void project(List<Offer> offers);
        void freeze();
        boolean matches(List<Offer> offers);
        long count();
    }
    static final class InvalidTarget extends RuntimeException {
        final String reason;
        InvalidTarget(String reason) { super(reason); this.reason = reason; }
    }
    enum Result { PROGRESSED, SNAPSHOT_VALIDATED, INVALID, RETRY, LEASE_LOST, BUSY_OR_TERMINAL }
    private final RebuildStore store;
    private final Target target;
    ShadowRebuild(RebuildStore store, Target target) { this.store = store; this.target = target; }

    Result step(UUID id) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Rebuild I/O must not run inside a database transaction.");
        }
        store.get(id); // Unknown job is distinct from a busy/terminal job.
        var claimed = store.claim(id);
        if (claimed.isEmpty()) return Result.BUSY_OR_TERMINAL;
        var claim = claimed.get();
        try {
            target.ensure(id);
            var page = store.page(claim);
            var offers = page.stream().map(RebuildStore.Item::offer).toList();
            boolean building = claim.job().state().equals("BUILDING");
            long previous = building ? claim.job().projected() : claim.job().validated();
            long through = page.isEmpty() ? previous : page.getLast().ordinal();
            if (page.isEmpty() && previous != claim.job().total()) throw new InvalidTarget("CONTENT_MISMATCH");
            if (building) {
                if (!offers.isEmpty()) target.project(offers);
            } else if (!offers.isEmpty()) {
                target.freeze();
                if (!target.matches(offers)) throw new InvalidTarget("CONTENT_MISMATCH");
            } else {
                target.freeze();
                if (target.count() != claim.job().total()) throw new InvalidTarget("COUNT_MISMATCH");
                return store.finish(claim) ? Result.SNAPSHOT_VALIDATED : Result.LEASE_LOST;
            }
            return store.checkpoint(claim, through) ? Result.PROGRESSED : Result.LEASE_LOST;
        } catch (InvalidTarget invalid) {
            return store.fail(claim, invalid.reason, true) ? Result.INVALID : Result.LEASE_LOST;
        } catch (RuntimeException transientFailure) {
            return store.fail(claim, "STEP_FAILED", false) ? Result.RETRY : Result.LEASE_LOST;
        }
        // Errors deliberately escape: a killed/crashed worker leaves its lease for recovery.
    }
}
