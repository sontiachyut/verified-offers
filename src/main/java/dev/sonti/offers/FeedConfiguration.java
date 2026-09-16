package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@Profile("postgres-local & !local-demo")
@ConditionalOnProperty(name = "offers.feeds.enabled", havingValue = "true")
class FeedConfiguration {
    @Bean FeedInput feedInput(Clock clock) { return new FeedInput(clock); }
    @Bean FeedStore feedStore(JdbcTemplate sql, PlatformTransactionManager manager, Clock clock, JsonMapper json) {
        var catalog = new PostgresCatalog(sql, new TransactionTemplate(manager), clock, Duration.ofMinutes(5), json);
        return new FeedStore(sql, manager, json, catalog);
    }
    @Bean FeedWorker feedWorker(FeedStore store) { return new FeedWorker(store); }
}
