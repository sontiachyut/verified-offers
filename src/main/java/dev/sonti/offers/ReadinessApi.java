package dev.sonti.offers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("postgres-local & !local-demo")
final class ReadinessApi {
    record Report(String status, Map<String, String> dependencies) {}
    private final Map<String, BooleanSupplier> probes;
    ReadinessApi(JdbcTemplate sql, ObjectProvider<OpenSearchIndex> index, Environment environment) {
        var checks = new LinkedHashMap<String, BooleanSupplier>();
        checks.put("postgres", () -> Boolean.TRUE.equals(sql.execute((ConnectionCallback<Boolean>) connection -> connection.isValid(2))));
        if (environment.getProperty("offers.search.enabled", Boolean.class, false))
            checks.put("search", () -> index.getObject().liveTarget() != null);
        for (String worker : List.of("publisher", "indexer")) {
            if (environment.getProperty("offers." + worker + ".enabled", Boolean.class, false)) {
                String servers = environment.getRequiredProperty("offers." + worker + ".bootstrap-servers");
                checks.put(worker + "Kafka", () -> topicExists(servers));
            }
        }
        probes = Collections.unmodifiableMap(checks);
    }
    @GetMapping("/api/v1/readiness")
    ResponseEntity<Report> readiness() {
        var result = inspect(probes);
        return ResponseEntity.status(result.status().equals("READY") ? 200 : 503).body(result);
    }
    static Report inspect(Map<String, BooleanSupplier> probes) {
        var statuses = new LinkedHashMap<String, String>();
        probes.forEach((name, probe) -> {
            boolean healthy;
            try { healthy = probe.getAsBoolean(); } catch (RuntimeException unavailable) { healthy = false; }
            statuses.put(name, healthy ? "UP" : "DOWN");
        });
        return new Report(statuses.containsValue("DOWN") ? "NOT_READY" : "READY", Map.copyOf(statuses));
    }
    private static boolean topicExists(String servers) {
        var admin = Admin.create(Map.of("bootstrap.servers", servers, "request.timeout.ms", 2000, "default.api.timeout.ms", 2000));
        try {
            var topic = admin.describeTopics(List.of(KafkaOfferSink.TOPIC)).allTopicNames().get(3, TimeUnit.SECONDS).get(KafkaOfferSink.TOPIC);
            return topic != null && !topic.partitions().isEmpty() && topic.partitions().stream().allMatch(p -> p.leader() != null && p.leader().id() >= 0);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        catch (Exception unavailable) { return false; }
        finally { admin.close(Duration.ofSeconds(1)); }
    }
}
