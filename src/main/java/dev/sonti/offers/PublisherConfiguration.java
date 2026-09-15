package dev.sonti.offers;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("postgres-local & !local-demo")
@ConditionalOnProperty(name = "offers.publisher.enabled", havingValue = "true")
class PublisherConfiguration {
    @Bean(destroyMethod = "close")
    KafkaOfferSink kafkaOfferSink(@Value("${offers.publisher.bootstrap-servers}") String servers) {
        return new KafkaOfferSink(servers);
    }

    @Bean
    PublisherPoller publisherPoller(JdbcTemplate sql, PlatformTransactionManager transactions,
            KafkaOfferSink sink, MeterRegistry meters) {
        return new PublisherPoller(new OutboxRelay(new PostgresOutbox(sql, transactions), sink), meters);
    }

    // Dependency on the poller makes scheduler destruction precede sink and DB destruction.
    @Bean
    ThreadPoolTaskScheduler offersPublisherScheduler(PublisherPoller poller) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("offers-publisher-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(25);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> startPublisher(ThreadPoolTaskScheduler offersPublisherScheduler,
            PublisherPoller poller) {
        return event -> offersPublisherScheduler.scheduleWithFixedDelay(poller, Duration.ofMillis(250));
    }
}
