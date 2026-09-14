package dev.sonti.offers;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short database transactions only; publication takes place after a claim commits. */
public final class PostgresOutbox {
    public record Delivery(UUID eventId, UUID leaseToken, String key, String payload, int attempt) {}
    public enum Failure { SEND_FAILED, SEND_INTERRUPTED }
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;

    public PostgresOutbox(JdbcTemplate sql, PlatformTransactionManager manager) {
        this.sql = Objects.requireNonNull(sql);
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Optional<Delivery> claim() {
        return tx.execute(status -> {
            // A process can die on its last attempt without recording a failure.
            sql.update("""
                    WITH exhausted AS (
                        SELECT event_id FROM outbox
                        WHERE published_at IS NULL AND quarantined_at IS NULL AND attempts=8
                        AND (lease_until IS NULL OR lease_until<=statement_timestamp())
                        ORDER BY next_attempt_at,created_at,event_id LIMIT 32 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE outbox o SET quarantined_at=statement_timestamp(),lease_token=NULL,
                        lease_until=NULL,last_error='ATTEMPTS_EXHAUSTED'
                    FROM exhausted e WHERE o.event_id=e.event_id
                    """);
            var rows = sql.query("""
                    WITH candidate AS (
                        SELECT event_id FROM outbox
                        WHERE published_at IS NULL AND quarantined_at IS NULL AND attempts<8
                        AND next_attempt_at<=statement_timestamp()
                        AND (lease_until IS NULL OR lease_until<=statement_timestamp())
                        ORDER BY next_attempt_at,created_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    UPDATE outbox o SET lease_token=?,lease_until=statement_timestamp()+interval '30 seconds',
                        attempts=attempts+1,last_error=NULL
                    FROM candidate c WHERE o.event_id=c.event_id
                    RETURNING o.event_id,o.lease_token,o.tenant_id,o.aggregate_id,o.payload::text,o.attempts
                    """, (rs, row) -> new Delivery(rs.getObject("event_id", UUID.class),
                    rs.getObject("lease_token", UUID.class), rs.getString("tenant_id") + ":" + rs.getString("aggregate_id"),
                    rs.getString("payload"), rs.getInt("attempts")), UUID.randomUUID());
            return rows.stream().findFirst();
        });
    }

    public boolean markPublished(Delivery event) {
        return Boolean.TRUE.equals(tx.execute(status -> sql.update("""
                UPDATE outbox SET published_at=statement_timestamp(),lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND lease_token=? AND lease_until>statement_timestamp()
                AND published_at IS NULL AND quarantined_at IS NULL
                """, event.eventId(), event.leaseToken()) == 1));
    }

    public boolean recordFailure(Delivery event, Failure failure) {
        Objects.requireNonNull(failure);
        return Boolean.TRUE.equals(tx.execute(status -> sql.update("""
                UPDATE outbox SET lease_token=NULL,lease_until=NULL,last_error=?,
                    next_attempt_at=statement_timestamp() +
                        (LEAST(60.0, power(2.0, attempts-1)) * ? * interval '1 second'),
                    quarantined_at=CASE WHEN attempts>=8 THEN statement_timestamp() ELSE NULL END
                WHERE event_id=? AND lease_token=? AND lease_until>statement_timestamp()
                AND published_at IS NULL AND quarantined_at IS NULL
                """, failure.name(), ThreadLocalRandom.current().nextDouble(0.5, 1.0),
                event.eventId(), event.leaseToken()) == 1));
    }

    /** Explicit local administration; replay and audit must commit together. */
    public boolean requeueQuarantined(UUID eventId, String reason) {
        Objects.requireNonNull(eventId);
        if (reason == null || reason.isBlank() || reason.length() > 300) {
            throw new IllegalArgumentException("Replay requires a reason of 1–300 characters.");
        }
        return Boolean.TRUE.equals(tx.execute(status -> {
            var attempts = sql.query("""
                    SELECT attempts FROM outbox WHERE event_id=? AND published_at IS NULL
                    AND quarantined_at IS NOT NULL FOR UPDATE
                    """, (rs, row) -> rs.getInt(1), eventId);
            if (attempts.isEmpty()) return false;
            sql.update("INSERT INTO outbox_replay(event_id,previous_attempts,reason) VALUES (?,?,?)",
                    eventId, attempts.getFirst(), reason);
            sql.update("""
                    UPDATE outbox SET attempts=0,quarantined_at=NULL,last_error=NULL,
                    lease_token=NULL,lease_until=NULL,next_attempt_at=statement_timestamp() WHERE event_id=?
                    """, eventId);
            return true;
        }));
    }
}
