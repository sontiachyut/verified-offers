package dev.sonti.offers;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Durable job/row receipts; a catalog mutation and its feed checkpoint share one transaction. */
final class FeedStore {
    record Job(UUID id, String tenantId, String merchantId, String source, String sha256, int bytes,
               int total, int processed, int applied, int replayed, int rejected, int cancelled,
               String state, int failures, String lastError, Instant availableAt, Instant leaseUntil, Instant createdAt) {}
    record Accepted(Job job, boolean created) {}
    record Claim(UUID id, UUID token) {}
    record Row(int number, String sha256, String offerId, Long version, Instant sourceUpdatedAt,
               String state, String reason, Instant finishedAt) {}
    record Rows(List<Row> rows, Integer nextAfter) {}
    record Jobs(List<Job> jobs, UUID nextAfter) {}
    record Action(long id, String action, String reason, Instant createdAt) {}
    static final class LeaseLost extends RuntimeException {}
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final PostgresCatalog catalog;
    FeedStore(JdbcTemplate sql, PlatformTransactionManager manager, JsonMapper json, PostgresCatalog catalog) {
        this.sql = sql; this.tx = new TransactionTemplate(manager); this.json = json; this.catalog = catalog;
    }
    Accepted submit(String tenant, String merchant, String key, String source, FeedInput.Upload upload) {
        scope(tenant, merchant); Input.identifier(key); Input.identifier(source);
        return tx.execute(status -> {
            sql.execute("SELECT pg_advisory_xact_lock(781349,2)");
            var existing = sql.query("SELECT * FROM feed_job WHERE tenant_id=? AND merchant_id=? AND idempotency_key=?",
                    FeedStore::job, tenant, merchant, key);
            if (!existing.isEmpty()) {
                Job job = existing.getFirst();
                if (!job.sha256().equals(upload.sha256()) || !job.source().equals(source)) throw new DomainException(409, "Feed idempotency key conflict.");
                return new Accepted(job, false);
            }
            if (sql.queryForObject("SELECT count(*) FROM feed_job", Integer.class) >= 100) throw new DomainException(429, "Retained feed-job budget reached.");
            UUID id = UUID.randomUUID();
            int rejected = (int) upload.rows().stream().filter(r -> r.error() != null).count();
            sql.update("""
                    INSERT INTO feed_job(job_id,tenant_id,merchant_id,idempotency_key,source_label,sha256,byte_count,total,processed,rejected,state)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, id, tenant, merchant, key, source, upload.sha256(), upload.bytes(), upload.rows().size(), rejected, rejected,
                    rejected == upload.rows().size() ? "COMPLETED_WITH_ERRORS" : "QUEUED");
            sql.batchUpdate("""
                    INSERT INTO feed_row(job_id,tenant_id,merchant_id,row_number,sha256,payload,offer_id,version,source_updated_at,state,reason,finished_at)
                    VALUES (?,?,?,?,?,?::jsonb,?,?,?,?,?,CASE WHEN ? THEN statement_timestamp() ELSE NULL END)
                    """, upload.rows(), 100, (statement, row) -> {
                Offer offer = row.offer();
                statement.setObject(1, id); statement.setString(2, tenant); statement.setString(3, merchant);
                statement.setInt(4, row.number()); statement.setString(5, row.sha256());
                statement.setString(6, offer == null ? null : json.writeValueAsString(offer));
                statement.setString(7, offer == null ? null : offer.offerId());
                statement.setObject(8, offer == null ? null : offer.version());
                statement.setTimestamp(9, offer == null ? null : Timestamp.from(offer.sourceUpdatedAt()));
                statement.setString(10, row.error() == null ? "PENDING" : "REJECTED"); statement.setString(11, row.error());
                statement.setBoolean(12, row.error() != null);
            });
            return new Accepted(get(tenant, merchant, id), true);
        });
    }
    Job get(String tenant, String merchant, UUID id) {
        scope(tenant, merchant);
        var jobs = sql.query("SELECT * FROM feed_job WHERE tenant_id=? AND merchant_id=? AND job_id=?", FeedStore::job, tenant, merchant, id);
        if (jobs.isEmpty()) throw new DomainException(404, "Feed job not found.");
        return jobs.getFirst();
    }
    Jobs list(String tenant, String merchant, UUID after, int limit) {
        scope(tenant, merchant); limit(limit);
        var jobs = sql.query("SELECT * FROM feed_job WHERE tenant_id=? AND merchant_id=? AND (?::uuid IS NULL OR job_id>?::uuid) ORDER BY job_id LIMIT ?",
                FeedStore::job, tenant, merchant, after, after, limit + 1);
        boolean more = jobs.size() > limit;
        var page = List.copyOf(jobs.subList(0, Math.min(limit, jobs.size())));
        return new Jobs(page, more ? page.getLast().id() : null);
    }
    Rows rows(String tenant, String merchant, UUID id, int after, int limit) {
        Job job = get(tenant, merchant, id); limit(limit);
        if (after < 0 || after > FeedInput.MAX_ROWS) throw new IllegalArgumentException("Invalid row cursor.");
        var rows = sql.query("SELECT * FROM feed_row WHERE job_id=? AND row_number>? ORDER BY row_number LIMIT ?",
                (rs, row) -> new Row(rs.getInt("row_number"), rs.getString("sha256"), rs.getString("offer_id"),
                        rs.getObject("version", Long.class), instant(rs, "source_updated_at"), rs.getString("state"), rs.getString("reason"), instant(rs, "finished_at")),
                id, after, limit);
        return new Rows(rows, !rows.isEmpty() && rows.getLast().number() < job.total() ? rows.getLast().number() : null);
    }
    List<Action> actions(String tenant, String merchant, UUID id) {
        get(tenant, merchant, id);
        return sql.query("SELECT * FROM feed_action WHERE job_id=? ORDER BY action_id LIMIT 20",
                (rs, row) -> new Action(rs.getLong("action_id"), rs.getString("action"), rs.getString("reason"), instant(rs, "created_at")), id);
    }
    Optional<Claim> claim() {
        UUID token = UUID.randomUUID();
        var ids = sql.queryForList("""
                UPDATE feed_job SET state='RUNNING',lease_token=?,lease_until=statement_timestamp()+interval '60 seconds'
                WHERE job_id=(SELECT job_id FROM feed_job WHERE state IN ('QUEUED','RUNNING') AND available_at<=statement_timestamp()
                    AND (lease_until IS NULL OR lease_until<=statement_timestamp()) ORDER BY available_at,created_at,job_id
                    FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING job_id
                """, UUID.class, token);
        return ids.isEmpty() ? Optional.empty() : Optional.of(new Claim(ids.getFirst(), token));
    }
    /** Returns false after terminal completion; business conflicts are receipts, SQL failures are not. */
    boolean processOne(Claim claim) {
        return tx.execute(status -> {
            Job job = own(claim);
            var pending = sql.queryForList("SELECT row_number,payload::text FROM feed_row WHERE job_id=? AND state='PENDING' ORDER BY row_number LIMIT 1", claim.id());
            if (pending.isEmpty()) throw new IllegalStateException("Pending feed row missing.");
            var row = pending.getFirst(); int number = (Integer) row.get("row_number");
            Offer offer = json.readValue((String) row.get("payload"), Offer.class);
            String result, reason = null;
            try { result = catalog.ingestInTransaction(offer).replay() ? "REPLAYED" : "APPLIED"; }
            catch (DomainException conflict) {
                if (conflict.status() != 409) throw conflict;
                result = "REJECTED"; reason = "VERSION_CONFLICT";
            } catch (IllegalArgumentException invalidTime) { result = "REJECTED"; reason = "FUTURE_SOURCE"; }
            boolean success = !result.equals("REJECTED");
            sql.update("""
                    UPDATE feed_row SET state=?,reason=?,receipt_offer_id=?,receipt_version=?,finished_at=statement_timestamp()
                    WHERE job_id=? AND row_number=? AND state='PENDING'
                    """, result, reason, success ? offer.offerId() : null, success ? offer.version() : null, claim.id(), number);
            String counter = switch (result) { case "APPLIED" -> "applied"; case "REPLAYED" -> "replayed"; default -> "rejected"; };
            boolean complete = job.processed() + 1 == job.total();
            String state = complete ? (job.rejected() + (success ? 0 : 1) == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS") : "RUNNING";
            sql.update("UPDATE feed_job SET processed=processed+1," + counter + "=" + counter + "+1,state=?,failures=0,last_error=NULL,"
                    + "lease_token=CASE WHEN ? THEN NULL ELSE lease_token END,lease_until=CASE WHEN ? THEN NULL ELSE lease_until END WHERE job_id=?",
                    state, complete, complete, claim.id());
            return !complete;
        });
    }
    void release(Claim claim) {
        sql.update("UPDATE feed_job SET state='QUEUED',lease_token=NULL,lease_until=NULL,available_at=statement_timestamp() WHERE job_id=? AND state='RUNNING' AND lease_token=?",
                claim.id(), claim.token());
    }
    void fail(Claim claim) {
        sql.update("""
                UPDATE feed_job SET state=CASE WHEN failures+1>=5 THEN 'PAUSED' ELSE 'QUEUED' END,
                    available_at=statement_timestamp()+make_interval(secs=>least(30,power(2,failures)::integer)),
                    failures=failures+1,last_error='DATABASE_ERROR',lease_token=NULL,lease_until=NULL
                WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>statement_timestamp()
                """, claim.id(), claim.token());
    }
    Job control(String tenant, String merchant, UUID id, String action, String reason) {
        scope(tenant, merchant); Input.identifier(reason);
        if (!List.of("RETRY", "CANCEL").contains(action)) throw new IllegalArgumentException("Invalid feed action.");
        return tx.execute(status -> {
            var jobs = sql.query("SELECT * FROM feed_job WHERE tenant_id=? AND merchant_id=? AND job_id=? FOR UPDATE", FeedStore::job, tenant, merchant, id);
            if (jobs.isEmpty()) throw new DomainException(404, "Feed job not found.");
            Job job = jobs.getFirst();
            if (List.of("COMPLETED", "COMPLETED_WITH_ERRORS", "CANCELLED").contains(job.state()) || (action.equals("RETRY") && !job.state().equals("PAUSED"))) {
                throw new DomainException(409, "Feed action not allowed in this state.");
            }
            if (sql.queryForObject("SELECT count(*) FROM feed_action WHERE job_id=?", Integer.class, id) >= 20) throw new DomainException(409, "Feed action budget reached.");
            sql.update("INSERT INTO feed_action(job_id,action,reason) VALUES (?,?,?)", id, action, reason);
            if (action.equals("RETRY")) {
                sql.update("UPDATE feed_job SET state='QUEUED',failures=0,last_error=NULL,available_at=statement_timestamp() WHERE job_id=?", id);
            } else {
                int cancelled = sql.update("UPDATE feed_row SET state='CANCELLED',reason='CANCELLED',finished_at=statement_timestamp() WHERE job_id=? AND state='PENDING'", id);
                sql.update("UPDATE feed_job SET state='CANCELLED',processed=processed+?,cancelled=cancelled+?,lease_token=NULL,lease_until=NULL WHERE job_id=?", cancelled, cancelled, id);
            }
            return get(tenant, merchant, id);
        });
    }
    private Job own(Claim claim) {
        var jobs = sql.query("SELECT * FROM feed_job WHERE job_id=? AND state='RUNNING' AND lease_token=? AND lease_until>statement_timestamp() FOR UPDATE",
                FeedStore::job, claim.id(), claim.token());
        if (jobs.isEmpty()) throw new LeaseLost();
        return jobs.getFirst();
    }
    private static void scope(String tenant, String merchant) { Input.identifier(tenant); Input.identifier(merchant); }
    private static void limit(int limit) { if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page size."); }
    private static Instant instant(ResultSet rs, String name) throws SQLException { var time = rs.getTimestamp(name); return time == null ? null : time.toInstant(); }
    private static Job job(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getObject("job_id", UUID.class), rs.getString("tenant_id"), rs.getString("merchant_id"), rs.getString("source_label"),
                rs.getString("sha256"), rs.getInt("byte_count"), rs.getInt("total"), rs.getInt("processed"), rs.getInt("applied"), rs.getInt("replayed"),
                rs.getInt("rejected"), rs.getInt("cancelled"), rs.getString("state"), rs.getInt("failures"), rs.getString("last_error"),
                instant(rs, "available_at"), instant(rs, "lease_until"), instant(rs, "created_at"));
    }
}
