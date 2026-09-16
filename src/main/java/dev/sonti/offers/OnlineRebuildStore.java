package dev.sonti.offers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

final class OnlineRebuildStore {
    record Run(UUID id, String state, String alias, String oldIndex, String endpoint, KafkaWindow.Boundary start,
               KafkaWindow.Boundary end, UUID candidate, Instant leaseUntil, String lastError) {}
    record Claim(Run run, UUID token) {}
    record Cursor(int partition, long next, long end) {}
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    final RebuildStore snapshots;
    OnlineRebuildStore(JdbcTemplate sql, PlatformTransactionManager manager, JsonMapper json) {
        this.sql = sql; this.tx = new TransactionTemplate(manager); this.json = json;
        snapshots = new RebuildStore(sql, manager, json);
    }
    Run create(String alias, String oldIndex, String endpoint, KafkaWindow.Boundary start) {
        return tx.execute(status -> {
            if (!Boolean.TRUE.equals(sql.queryForObject("SELECT blocked_by IS NULL FROM index_route WHERE alias=? AND protocol=1",
                    Boolean.class, alias))) throw new DomainException(409, "Live indexer must be upgraded and unpaused.");
            var snapshot = snapshots.create();
            sql.update("INSERT INTO index_online_run(run_id,live_alias,old_index,endpoint,kafka_start) VALUES (?,?,?,?,?::jsonb)",
                    snapshot.id(), alias, oldIndex, endpoint, json.writeValueAsString(start));
            sql.update("""
                    INSERT INTO index_online_expected SELECT job_id,tenant_id,merchant_id,offer_id,version
                    FROM index_rebuild_item WHERE job_id=?
                    """, snapshot.id());
            return get(snapshot.id());
        });
    }
    Run get(UUID id) {
        var rows = sql.query("SELECT * FROM index_online_run WHERE run_id=?", this::map, id);
        if (rows.isEmpty()) throw new DomainException(404, "Coordinated rebuild not found.");
        return rows.getFirst();
    }
    Optional<Claim> claim(UUID id) {
        UUID token = UUID.randomUUID();
        var rows = sql.query("""
                UPDATE index_online_run SET lease_token=?,lease_until=statement_timestamp()+interval '60 seconds',last_error=NULL
                WHERE run_id=? AND state NOT IN ('ACTIVE','ABORTED')
                  AND (lease_until IS NULL OR lease_until<=statement_timestamp()) RETURNING *
                """, this::map, token, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(new Claim(rows.getFirst(), token));
    }
    void release(Claim claim, String reason) {
        sql.update("""
                UPDATE index_online_run SET lease_token=NULL,lease_until=NULL,last_error=?
                WHERE run_id=? AND lease_token=? AND lease_until>statement_timestamp()
                """, reason, claim.run().id(), claim.token());
    }
    private void own(Claim claim) {
        var rows = sql.queryForList("""
                SELECT run_id FROM index_online_run WHERE run_id=? AND lease_token=?
                AND lease_until>statement_timestamp() AND state=? FOR UPDATE
                """, UUID.class, claim.run().id(), claim.token(), claim.run().state());
        if (rows.isEmpty()) throw new DomainException(409, "Rebuild lease lost.");
    }
    void pause(Claim claim) {
        tx.executeWithoutResult(status -> {
            own(claim);
            if (sql.update("UPDATE index_route SET blocked_by=? WHERE alias=? AND (blocked_by IS NULL OR blocked_by=?)",
                    claim.run().id(), claim.run().alias(), claim.run().id()) != 1) throw new DomainException(409, "Another handoff owns the gate.");
            sql.update("UPDATE index_online_run SET state='PAUSED' WHERE run_id=?", claim.run().id());
        });
    }
    void requirePause(Run run) {
        if (!Boolean.TRUE.equals(sql.queryForObject("SELECT blocked_by=? FROM index_route WHERE alias=?", Boolean.class, run.id(), run.alias()))) {
            throw new DomainException(409, "Handoff no longer owns the pause.");
        }
    }
    void seal(Claim claim, KafkaWindow.Boundary end) {
        KafkaWindow.validateSeal(claim.run().start(), end);
        tx.executeWithoutResult(status -> {
            own(claim); requirePause(claim.run());
            for (int i = 0; i < end.offsets().size(); i++) {
                var start = claim.run().start().offsets().get(i); var stop = end.offsets().get(i);
                sql.update("INSERT INTO index_online_cursor VALUES (?,?,?,?)", claim.run().id(), start.partition(), start.end(), stop.end());
            }
            sql.update("UPDATE index_online_run SET state='REPLAYING',kafka_end=?::jsonb WHERE run_id=?",
                    json.writeValueAsString(end), claim.run().id());
        });
    }
    List<Cursor> cursors(UUID id) {
        return sql.query("SELECT * FROM index_online_cursor WHERE run_id=? ORDER BY partition_id",
                (rs, row) -> new Cursor(rs.getInt("partition_id"), rs.getLong("next_offset"), rs.getLong("end_offset")), id);
    }
    void replay(Claim claim, Cursor cursor, List<ConsumerRecord<String, String>> records) {
        if (records.isEmpty() || records.size() > 100) throw new IllegalArgumentException("Invalid replay batch.");
        tx.executeWithoutResult(status -> {
            own(claim); requirePause(claim.run());
            long next = cursor.next();
            for (var record : records) {
                if (!KafkaOfferSink.TOPIC.equals(record.topic()) || record.partition() != cursor.partition()
                        || record.offset() != next || next >= cursor.end()) throw new DomainException(409, "Replay cursor mismatch.");
                Offer offer = OfferEvent.parse(record.key(), record.value());
                var history = sql.query("""
                        SELECT payload::text FROM offer_version WHERE tenant_id=? AND merchant_id=? AND offer_id=? AND version=?
                        """, (rs, row) -> json.readValue(rs.getString(1), Offer.class), offer.tenantId(), offer.merchantId(), offer.offerId(), offer.version());
                if (history.size() != 1 || !history.getFirst().equals(offer)) throw new IllegalArgumentException("Event has no matching committed source.");
                sql.update("""
                        INSERT INTO index_online_expected VALUES (?,?,?,?,?)
                        ON CONFLICT (run_id,tenant_id,merchant_id,offer_id) DO UPDATE SET version=EXCLUDED.version
                        WHERE index_online_expected.version<EXCLUDED.version
                        """, claim.run().id(), offer.tenantId(), offer.merchantId(), offer.offerId(), offer.version());
                next++;
            }
            if (sql.queryForObject("SELECT count(*) FROM index_online_expected WHERE run_id=?", Long.class, claim.run().id()) > 100000) {
                throw new DomainException(409, "Expected catalog exceeds local cap.");
            }
            if (sql.update("UPDATE index_online_cursor SET next_offset=? WHERE run_id=? AND partition_id=? AND next_offset=?",
                    next, claim.run().id(), cursor.partition(), cursor.next()) != 1) throw new DomainException(409, "Replay cursor changed.");
        });
    }
    void materialize(Claim claim) {
        tx.executeWithoutResult(status -> {
            own(claim); requirePause(claim.run());
            if (cursors(claim.run().id()).stream().anyMatch(c -> c.next() != c.end())) throw new DomainException(409, "Replay incomplete.");
            var candidate = snapshots.createCandidate(claim.run().id());
            sql.update("UPDATE index_online_run SET state='BUILDING',candidate_id=? WHERE run_id=?", candidate.id(), claim.run().id());
        });
    }
    void switching(Claim claim) {
        tx.executeWithoutResult(status -> {
            own(claim); requirePause(claim.run());
            if (!snapshots.get(claim.run().candidate()).state().equals("SNAPSHOT_VALIDATED")) throw new DomainException(409, "Candidate not validated.");
            sql.update("UPDATE index_online_run SET state='SWITCHING' WHERE run_id=?", claim.run().id());
        });
    }
    void finish(Claim claim) {
        tx.executeWithoutResult(status -> {
            own(claim); requirePause(claim.run());
            if (!claim.run().state().equals("SWITCHING")) throw new DomainException(409, "Not switching.");
            sql.update("UPDATE index_online_run SET state='ACTIVE',lease_token=NULL,lease_until=NULL WHERE run_id=?", claim.run().id());
            sql.update("UPDATE index_route SET blocked_by=NULL WHERE alias=? AND blocked_by=?", claim.run().alias(), claim.run().id());
        });
    }
    void abort(Claim claim) {
        tx.executeWithoutResult(status -> {
            own(claim);
            if (claim.run().state().equals("SWITCHING")) throw new DomainException(409, "Switching must be reconciled, not rolled back.");
            sql.update("UPDATE index_online_run SET state='ABORTED',lease_token=NULL,lease_until=NULL WHERE run_id=?", claim.run().id());
            sql.update("UPDATE index_route SET blocked_by=NULL WHERE alias=? AND blocked_by=?", claim.run().alias(), claim.run().id());
        });
    }
    private Run map(ResultSet rs, int row) throws SQLException {
        var lease = rs.getTimestamp("lease_until"); var end = rs.getString("kafka_end");
        return new Run(rs.getObject("run_id", UUID.class), rs.getString("state"), rs.getString("live_alias"),
                rs.getString("old_index"), rs.getString("endpoint"), json.readValue(rs.getString("kafka_start"), KafkaWindow.Boundary.class),
                end == null ? null : json.readValue(end, KafkaWindow.Boundary.class), rs.getObject("candidate_id", UUID.class),
                lease == null ? null : lease.toInstant(), rs.getString("last_error"));
    }
}
