package dev.sonti.offers;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

/** Capped gauges are pressure indicators, never represented as unbounded exact totals. */
@Configuration(proxyBeanMethods = false)
@Profile("postgres-local & !local-demo")
class OperationalMetrics {
    OperationalMetrics(JdbcTemplate sql, MeterRegistry meters) {
        register(sql, meters, "offers.outbox.pending.capped", "SELECT count(*) FROM (SELECT 1 FROM outbox WHERE published_at IS NULL AND quarantined_at IS NULL LIMIT 10001) bounded");
        register(sql, meters, "offers.outbox.quarantine.capped", "SELECT count(*) FROM (SELECT 1 FROM outbox WHERE quarantined_at IS NOT NULL LIMIT 10001) bounded");
        register(sql, meters, "offers.index.quarantine.capped", "SELECT count(*) FROM (SELECT 1 FROM index_quarantine LIMIT 10001) bounded");
        register(sql, meters, "offers.feeds.pending.capped", "SELECT count(*) FROM (SELECT 1 FROM feed_job WHERE state IN ('QUEUED','RUNNING','PAUSED') LIMIT 101) bounded");
    }
    private static void register(JdbcTemplate sql, MeterRegistry meters, String name, String query) {
        Gauge.builder(name, sql, jdbc -> {
            try { return jdbc.queryForObject(query, Double.class); }
            catch (RuntimeException unavailable) { return Double.NaN; }
        }).description("Capped local pressure indicator; see OPERATIONS.md").register(meters);
    }
}
