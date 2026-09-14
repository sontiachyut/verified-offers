package dev.sonti.offers;

import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One bounded unit of work. No scheduler or destination is enabled implicitly. */
public final class OutboxRelay {
    @FunctionalInterface
    public interface AcknowledgingSink {
        /** Return only after acknowledgement; implementations must bound their I/O below the 30s lease. */
        void publish(PostgresOutbox.Delivery event) throws Exception;
    }
    public enum Result { IDLE, PUBLISHED, RETRY_OR_QUARANTINED, LEASE_LOST }
    private final PostgresOutbox outbox;
    private final AcknowledgingSink sink;

    public OutboxRelay(PostgresOutbox outbox, AcknowledgingSink sink) {
        this.outbox = Objects.requireNonNull(outbox);
        this.sink = Objects.requireNonNull(sink);
    }

    public Result publishNext() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Do not publish inside a database transaction.");
        }
        var claim = outbox.claim();
        if (claim.isEmpty()) return Result.IDLE;
        var event = claim.get();
        try {
            sink.publish(event);
        } catch (InterruptedException interrupted) {
            try { return fail(event, PostgresOutbox.Failure.SEND_INTERRUPTED); }
            finally { Thread.currentThread().interrupt(); }
        } catch (Exception failure) {
            return fail(event, PostgresOutbox.Failure.SEND_FAILED);
        }
        // Database failure here must leave the lease for replay, not pretend the send failed.
        return outbox.markPublished(event) ? Result.PUBLISHED : Result.LEASE_LOST;
    }

    private Result fail(PostgresOutbox.Delivery event, PostgresOutbox.Failure reason) {
        return outbox.recordFailure(event, reason) ? Result.RETRY_OR_QUARANTINED : Result.LEASE_LOST;
    }
}
