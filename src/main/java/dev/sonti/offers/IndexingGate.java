package dev.sonti.offers;

import org.springframework.jdbc.core.JdbcTemplate;

/** Checked after fetching a record, before projection/quarantine and Kafka acknowledgement. */
final class IndexingGate implements Runnable {
    private final JdbcTemplate sql;
    private final String alias;
    IndexingGate(JdbcTemplate sql, String alias) {
        this.sql = sql;
        this.alias = alias;
        sql.update("INSERT INTO index_route(alias) VALUES (?) ON CONFLICT DO NOTHING", alias);
    }
    @Override public void run() {
        if (!Boolean.TRUE.equals(sql.queryForObject("SELECT blocked_by IS NULL FROM index_route WHERE alias=? AND protocol=1",
                Boolean.class, alias))) throw new DomainException(503, "Indexing paused for coordinated handoff.");
    }
}
