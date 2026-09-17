package dev.sonti.offers;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Audited operator association to immutable source; never interprets poison as trusted data. */
final class IndexReconciliation {
    record Position(String topic, int partition, long offset) {
        Position {
            if (topic == null || !topic.matches("[A-Za-z0-9._-]{1,249}") || partition < 0 || offset < 0)
                throw new IllegalArgumentException("Valid record position required.");
        }
    }
    record Intent(UUID id, Position position, String tenant, String merchant, String offer,
            long version, String operator, String reason, int attempts, Instant completedAt) {}
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    IndexReconciliation(JdbcTemplate sql, TransactionTemplate tx, JsonMapper json) { this.sql = sql; this.tx = tx; this.json = json; }
    Intent prepare(Position position, String tenant, String merchant, String offer, String operator, String reason) {
        Input.identifier(tenant); Input.identifier(merchant); Input.identifier(offer);
        Input.identifier(operator); Input.identifier(reason);
        return tx.execute(status -> {
            var quarantine = sql.query("SELECT reason FROM index_quarantine WHERE topic=? AND partition_id=? AND record_offset=? FOR UPDATE",
                    (rs, row) -> rs.getString(1), position.topic(), position.partition(), position.offset());
            if (quarantine.isEmpty()) throw new DomainException(404, "Quarantine record not found.");
            var existing = sql.query("SELECT reconciliation_id FROM index_reconciliation WHERE topic=? AND partition_id=? AND record_offset=?",
                    (rs, row) -> rs.getObject(1, UUID.class), position.topic(), position.partition(), position.offset());
            if (!existing.isEmpty()) {
                var intent = get(existing.getFirst());
                if (!tenant.equals(intent.tenant()) || !merchant.equals(intent.merchant()) || !offer.equals(intent.offer())
                        || !operator.equals(intent.operator()) || !reason.equals(intent.reason()))
                    throw new DomainException(409, "Reconciliation identity already assigned.");
                return intent;
            }
            var versions = sql.query("SELECT version FROM offer_head WHERE tenant_id=? AND merchant_id=? AND offer_id=?",
                    (rs, row) -> rs.getLong(1), tenant, merchant, offer);
            if (versions.isEmpty()) throw new DomainException(404, "Authoritative offer not found.");
            UUID id = UUID.randomUUID();
            sql.update("""
                    INSERT INTO index_reconciliation(reconciliation_id,topic,partition_id,record_offset,
                        tenant_id,merchant_id,offer_id,version,operator_label,reason) VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, id, position.topic(), position.partition(), position.offset(), tenant, merchant, offer,
                    versions.getFirst(), operator, reason);
            return get(id);
        });
    }
    Intent get(UUID id) {
        var rows = sql.query("SELECT * FROM index_reconciliation WHERE reconciliation_id=?", (rs, row) -> {
            var completed = rs.getTimestamp("completed_at");
            return new Intent(rs.getObject("reconciliation_id", UUID.class), new Position(rs.getString("topic"),
                    rs.getInt("partition_id"), rs.getLong("record_offset")), rs.getString("tenant_id"), rs.getString("merchant_id"),
                    rs.getString("offer_id"), rs.getLong("version"), rs.getString("operator_label"), rs.getString("reason"),
                    rs.getInt("attempts"), completed == null ? null : completed.toInstant());
        }, id);
        if (rows.isEmpty()) throw new DomainException(404, "Reconciliation not found.");
        return rows.getFirst();
    }
    /** Network I/O follows committed attempt evidence. Crashes replay a full immutable source snapshot. */
    Intent step(UUID id, Consumer<Offer> projection, Runnable gate) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Do not project within a database transaction.");
        gate.run();
        var intent = tx.execute(status -> {
            sql.queryForObject("SELECT reconciliation_id FROM index_reconciliation WHERE reconciliation_id=? FOR UPDATE", UUID.class, id);
            var current = get(id);
            if (current.completedAt() != null) return current;
            if (current.attempts() >= 8) throw new DomainException(409, "Reconciliation attempt budget exhausted; operator review required.");
            sql.update("UPDATE index_reconciliation SET attempts=attempts+1 WHERE reconciliation_id=?", id);
            return get(id);
        });
        if (intent.completedAt() != null) return intent;
        String payload = sql.queryForObject("SELECT payload::text FROM offer_version WHERE tenant_id=? AND merchant_id=? AND offer_id=? AND version=?",
                String.class, intent.tenant(), intent.merchant(), intent.offer(), intent.version());
        projection.accept(json.readValue(payload, Offer.class));
        sql.update("UPDATE index_reconciliation SET completed_at=statement_timestamp() WHERE reconciliation_id=? AND completed_at IS NULL", id);
        return get(id);
    }
}
