package dev.sonti.offers;

import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class DatabaseRolesIT extends PostgresFixture {
    @Test void runtimeCanIngestAndPublishButCannotDestroyHistoryOrAdministerSchema() throws Exception {
        sql.execute(Files.readString(Path.of("ops/database-roles.sql")));
        sql.execute(Files.readString(Path.of("ops/database-roles.sql"))); // Administrative setup is repeatable.
        String password = UUID.randomUUID().toString();
        sql.execute("CREATE ROLE offers_test LOGIN PASSWORD '" + password + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION");
        sql.execute("GRANT offers_runtime TO offers_test");
        var config = new HikariConfig(); config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername("offers_test"); config.setPassword(password); config.setMaximumPoolSize(2);
        try (var runtime = new HikariDataSource(config)) {
            var jdbc = new JdbcTemplate(runtime); var manager = new JdbcTransactionManager(runtime);
            var catalog = new PostgresCatalog(jdbc, new TransactionTemplate(manager), Clock.systemUTC(), Duration.ofMinutes(5), JsonMapper.builder().build());
            var offer = new Offer("roles", "merchant", "keyboard", 1, "Keyboard", 1999, "USD", 1, Instant.now(), false);
            assertThat(catalog.ingest(offer)).isEqualTo(offer);
            assertThat(catalog.ingest(offer)).isEqualTo(offer);
            var outbox = new PostgresOutbox(jdbc, manager);
            assertThat(outbox.markPublished(outbox.claim().orElseThrow())).isTrue();
            for (String denied : new String[] {"DELETE FROM offer_version", "TRUNCATE offer_key CASCADE", "UPDATE offer_version SET price_minor=0",
                    "CREATE TABLE forbidden(id int)", "ALTER TABLE offer_version DISABLE TRIGGER ALL", "SELECT * FROM flyway_schema_history",
                    "SET ROLE offers_operator", "CREATE ROLE escalation"})
                assertThatThrownBy(() -> jdbc.execute(denied)).as(denied).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }
}
