package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@Profile("postgres-local")
class PostgresConfiguration {
    @Bean
    OfferCatalog postgresCatalog(JdbcTemplate sql, PlatformTransactionManager manager, Clock clock, JsonMapper json) {
        return new PostgresCatalog(sql, new TransactionTemplate(manager), clock, Duration.ofMinutes(5), json);
    }
}
