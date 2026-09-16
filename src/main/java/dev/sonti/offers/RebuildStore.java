package dev.sonti.offers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Durable immutable snapshot references; short transactions, never network I/O. */
final class RebuildStore {
    static final int PAGE_SIZE = 100;
    record Job(UUID id, String state, Instant snapshotAt, long total, long projected, long validated,
               Instant leaseUntil, String lastError) {
        String shadowAlias() { return "offers-build-" + id; }
    }
    record Claim(Job job, UUID token) {}
    record Item(long ordinal, Offer offer) {}
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final int snapshotLimit;

    RebuildStore(JdbcTemplate sql, PlatformTransactionManager manager, JsonMapper json) {
        this(sql, manager, json, 100000);
    }
    RebuildStore(JdbcTemplate sql, PlatformTransactionManager manager, JsonMapper json, int snapshotLimit) {
        if (snapshotLimit < 1 || snapshotLimit > 100000) throw new IllegalArgumentException("Invalid snapshot limit.");
        this.sql = sql;
        this.tx = new TransactionTemplate(manager);
        this.json = json;
        this.snapshotLimit = snapshotLimit;
    }

    Job create() {
        return tx.execute(status -> {
            // Serialize only job creation, so simultaneous creators cannot exceed the disk budget.
            sql.execute("SELECT pg_advisory_xact_lock(781349,1)");
            if (sql.queryForObject("SELECT count(*) FROM index_rebuild", Long.class) >= 10) {
                throw new DomainException(409, "Ten retained rebuild jobs reached. Explicit retention review required.");
            }
            UUID id = UUID.randomUUID();
            sql.update("INSERT INTO index_rebuild(job_id) VALUES (?)", id);
            long count = sql.queryForObject("""
                    WITH captured AS (
                        INSERT INTO index_rebuild_item(job_id,ordinal,tenant_id,merchant_id,offer_id,version)
                        SELECT ?,row_number() OVER (ORDER BY tenant_id,merchant_id,offer_id),
                               tenant_id,merchant_id,offer_id,version
                        FROM offer_head ORDER BY tenant_id,merchant_id,offer_id LIMIT ?
                        RETURNING ordinal
                    ), marked AS (
                        UPDATE index_rebuild SET snapshot_at=statement_timestamp() WHERE job_id=? RETURNING job_id
                    ) SELECT count(*) FROM captured
                    """, Long.class, id, snapshotLimit + 1, id);
            if (count > snapshotLimit) throw new DomainException(409, "Configured local snapshot cap exceeded.");
            sql.update("UPDATE index_rebuild SET total=? WHERE job_id=?", count, id);
            return get(id);
        });
    }

    Job get(UUID id) {
        var jobs = sql.query("SELECT * FROM index_rebuild WHERE job_id=?", RebuildStore::job, id);
        if (jobs.isEmpty()) throw new DomainException(404, "Rebuild job not found.");
        return jobs.getFirst();
    }

    Optional<Claim> claim(UUID id) {
        UUID token = UUID.randomUUID();
        var jobs = sql.query("""
                UPDATE index_rebuild SET lease_token=?,lease_until=statement_timestamp()+interval '60 seconds',last_error=NULL
                WHERE job_id=? AND state IN ('BUILDING','VALIDATING')
                  AND (lease_until IS NULL OR lease_until <= statement_timestamp()) RETURNING *
                """, RebuildStore::job, token, id);
        return jobs.isEmpty() ? Optional.empty() : Optional.of(new Claim(jobs.getFirst(), token));
    }

    List<Item> page(Claim claim) {
        long after = claim.job().state().equals("BUILDING") ? claim.job().projected() : claim.job().validated();
        return sql.query("""
                SELECT i.ordinal,v.payload::text FROM index_rebuild_item i JOIN offer_version v
                USING (tenant_id,merchant_id,offer_id,version)
                WHERE i.job_id=? AND i.ordinal>? ORDER BY i.ordinal LIMIT 100
                """, (rs, row) -> new Item(rs.getLong(1), json.readValue(rs.getString(2), Offer.class)), claim.job().id(), after);
    }

    boolean checkpoint(Claim claim, long ordinal) {
        boolean building = claim.job().state().equals("BUILDING");
        long previous = building ? claim.job().projected() : claim.job().validated();
        if (ordinal < previous || ordinal > claim.job().total() || ordinal - previous > PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid rebuild checkpoint.");
        }
        String column = building ? "projected" : "validated";
        String state = building && ordinal == claim.job().total() ? "VALIDATING" : claim.job().state();
        return sql.update("UPDATE index_rebuild SET " + column + "=?,state=?,lease_token=NULL,lease_until=NULL,last_error=NULL "
                + "WHERE job_id=? AND lease_token=? AND lease_until>statement_timestamp() AND state=? AND " + column + "=?",
                ordinal, state, claim.job().id(), claim.token(), claim.job().state(), previous) == 1;
    }

    boolean finish(Claim claim) {
        return sql.update("""
                UPDATE index_rebuild SET state='SNAPSHOT_VALIDATED',lease_token=NULL,lease_until=NULL,last_error=NULL
                WHERE job_id=? AND lease_token=? AND lease_until>statement_timestamp()
                    AND state='VALIDATING' AND projected=total AND validated=total
                """, claim.job().id(), claim.token()) == 1;
    }

    boolean fail(Claim claim, String reason, boolean invalid) {
        if (!List.of("STEP_FAILED", "OWNERSHIP_MISMATCH", "CONTENT_MISMATCH", "COUNT_MISMATCH").contains(reason)) {
            throw new IllegalArgumentException("Invalid failure reason.");
        }
        return sql.update("UPDATE index_rebuild SET state=?,last_error=?,lease_token=NULL,lease_until=NULL "
                        + "WHERE job_id=? AND lease_token=? AND lease_until>statement_timestamp()",
                invalid ? "INVALID" : claim.job().state(), reason, claim.job().id(), claim.token()) == 1;
    }

    private static Job job(ResultSet rs, int row) throws SQLException {
        var lease = rs.getTimestamp("lease_until");
        return new Job(rs.getObject("job_id", UUID.class), rs.getString("state"), rs.getTimestamp("snapshot_at").toInstant(),
                rs.getLong("total"), rs.getLong("projected"), rs.getLong("validated"),
                lease == null ? null : lease.toInstant(), rs.getString("last_error"));
    }
}
