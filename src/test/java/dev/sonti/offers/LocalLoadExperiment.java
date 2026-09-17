package dev.sonti.offers;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in experiment: -Dit.test=LocalLoadExperiment. Never a silent skip in verify. */
class LocalLoadExperiment extends PostgresFixture {
    record Sample(String operation, int sequence, long startedMicros, double millis, boolean success, String failure) {}
    @Test void boundedMixedWorkloadProducesRawEvidence() throws Exception {
        final int offers = 250, merchants = 10, seconds = 20, searchRate = 10, updateRate = 2;
        var json = JsonMapper.builder().build();
        try (var search = SearchTestContainer.create(); var broker = new KafkaContainer(DockerImageName.parse(
                "apache/kafka@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0").asCompatibleSubstituteFor("apache/kafka"))
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(new com.github.dockerjava.api.model.PortBinding(
                        com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1", 0), new com.github.dockerjava.api.model.ExposedPort(9092))))) {
            search.start(); broker.start();
            try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(KafkaOfferSink.TOPIC, 3, (short) 1))).all().get(15, TimeUnit.SECONDS);
            }
            String endpoint = "http://127.0.0.1:" + search.getMappedPort(9200);
            try (var index = new OpenSearchIndex(endpoint, "load", json)) {
                index.create();
                var catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
                for (int i = 0; i < offers; i++) { var offer = fixture(i, 1, merchants); catalog.ingest(offer); index.project(offer); }
                // Offline bootstrap, excluded from measured workload. Deltas below use the real pipeline.
                sql.update("UPDATE outbox SET published_at=statement_timestamp()"); index.refresh();
                try (var app = new RunningApplication("--offers.search.enabled=true", "--offers.search.endpoint=" + endpoint,
                        "--offers.search.index=load", "--offers.publisher.enabled=true", "--offers.publisher.bootstrap-servers=" + broker.getBootstrapServers(),
                        "--offers.indexer.enabled=true", "--offers.indexer.bootstrap-servers=" + broker.getBootstrapServers(), "--offers.indexer.group=load-test")) {
                    for (int i = 0; i < 5; i++) searchAndClose(app); // Small explicit warmup, no measured samples.
                    var samples = Collections.synchronizedList(new ArrayList<Sample>());
                    var slots = new Semaphore(2); var futures = new ArrayList<Future<?>>(); int dropped = 0;
                    long started = System.nanoTime();
                    try (var workers = Executors.newFixedThreadPool(2)) {
                        for (int tick = 0; tick < seconds * searchRate; tick++) {
                            long remaining = started + tick * 100_000_000L - System.nanoTime();
                            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                            for (String operation : tick % (searchRate / updateRate) == 0 ? List.of("search", "update") : List.of("search")) {
                                int sequence = tick;
                                if (!slots.tryAcquire()) { dropped++; continue; }
                                futures.add(workers.submit(() -> {
                                    long requestStart = System.nanoTime(); boolean success = false; String failure = "NONE";
                                    try {
                                        if (operation.equals("search")) searchAndClose(app);
                                        else app.request("PUT", "/api/v1/offers", fixture(sequence, 2, merchants), 200);
                                        success = true;
                                    } catch (Throwable problem) { failure = problem instanceof AssertionError ? "HTTP_OR_CONTRACT" : "REQUEST_FAILED"; }
                                    finally {
                                        samples.add(new Sample(operation, sequence, (requestStart - started) / 1000,
                                                (System.nanoTime() - requestStart) / 1_000_000d, success, failure)); slots.release();
                                    }
                                }));
                            }
                        }
                        for (var future : futures) future.get(15, TimeUnit.SECONDS);
                    }
                    double measuredSeconds = (System.nanoTime() - started) / 1_000_000_000d;
                    long pending = sql.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
                    var report = new LinkedHashMap<String, Object>();
                    report.put("kind", "bounded-local-mixed-http"); report.put("gitCommit", gitCommit()); report.put("recordedAt", Instant.now().toString());
                    report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
                    report.put("javaVersion", System.getProperty("java.version")); report.put("effectiveProcessors", Runtime.getRuntime().availableProcessors());
                    report.put("seed", 20260916); report.put("offers", offers); report.put("merchants", merchants);
                    report.put("scheduledSeconds", seconds); report.put("measuredSeconds", measuredSeconds);
                    report.put("offeredSearchPerSecond", searchRate); report.put("offeredUpdatePerSecond", updateRate);
                    report.put("maxInFlight", 2); report.put("warmupSearches", 5); report.put("driverDropped", dropped);
                    report.put("outboxPendingAtEnd", pending); report.put("search", summary(samples, "search", measuredSeconds));
                    report.put("update", summary(samples, "update", measuredSeconds));
                    report.put("samples", samples.stream().sorted(Comparator.comparingLong(Sample::startedMicros)).toList());
                    report.put("largeScaleTargetDemonstrated", false); report.put("authenticationEnabled", false);
                    Files.createDirectories(Path.of("target/validation"));
                    json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/validation/load.json").toFile(), report);
                    assertThat(samples).isNotEmpty(); // Performance results are observations, not fabricated SLO guarantees.
                }
            }
        }
    }
    private static Offer fixture(int id, long version, int merchants) {
        return new Offer("load", "merchant-" + id % merchants, "item-" + id, version, "Mechanical keyboard model " + id,
                1000 + version, "USD", 5, Instant.now(), false);
    }
    private static void searchAndClose(RunningApplication app) throws Exception {
        String path = "/api/v1/search?tenantId=load&q=keyboard&limit=10";
        var page = app.request("GET", path, null, 200);
        for (var result : page.path("results")) assertThat(result.path("verification").path("outcome").asString()).isEqualTo("VERIFIED");
        if (!page.path("nextCursor").isNull()) app.request("DELETE", path + "&cursor=" + page.path("nextCursor").asString(), null, 200);
    }
    private static Map<String, Object> summary(List<Sample> samples, String operation, double seconds) {
        var rows = samples.stream().filter(s -> s.operation().equals(operation)).toList();
        var times = rows.stream().mapToDouble(Sample::millis).sorted().toArray();
        long succeeded = rows.stream().filter(Sample::success).count();
        return Map.of("attempted", rows.size(), "succeeded", succeeded, "failed", rows.size() - succeeded,
                "successPerSecond", succeeded / seconds, "p50Millis", percentile(times, .50),
                "p95Millis", percentile(times, .95), "p99Millis", percentile(times, .99));
    }
    private static double percentile(double[] values, double quantile) { return values.length == 0 ? 0 : values[(int) Math.ceil(values.length * quantile) - 1]; }
    private static String gitCommit() throws Exception {
        var process = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0) throw new IllegalStateException("Commit metadata unavailable.");
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
    }
}
