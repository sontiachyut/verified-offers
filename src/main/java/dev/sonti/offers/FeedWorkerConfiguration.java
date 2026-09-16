package dev.sonti.offers;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@Profile("postgres-local & !local-demo")
@ConditionalOnProperty(name = {"offers.feeds.enabled", "offers.feeds.worker-enabled"}, havingValue = "true")
class FeedWorkerConfiguration {
    @Bean FeedPoller feedPoller(FeedWorker worker, MeterRegistry meters) { return new FeedPoller(worker, meters); }
    @Bean ThreadPoolTaskScheduler offersFeedScheduler(FeedPoller poller) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1); scheduler.setThreadNamePrefix("offers-feeds-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true); scheduler.setAwaitTerminationSeconds(25);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }
    @Bean ApplicationListener<ApplicationReadyEvent> startFeeds(ThreadPoolTaskScheduler offersFeedScheduler, FeedPoller poller) {
        return event -> offersFeedScheduler.scheduleWithFixedDelay(poller, Duration.ofMillis(250));
    }
}
