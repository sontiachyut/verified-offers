package dev.sonti.offers;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("postgres-local & !local-demo")
@ConditionalOnProperty(name = "offers.indexer.enabled", havingValue = "true")
class IndexerConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    IndexConsumer offerIndexConsumer(@Value("${offers.indexer.bootstrap-servers}") String servers,
            @Value("${offers.indexer.group}") String group, OpenSearchIndex index, JdbcTemplate sql, MeterRegistry meters) {
        return new IndexConsumer(servers, group, new IndexRecordHandler(index::project, sql), meters);
    }
}
