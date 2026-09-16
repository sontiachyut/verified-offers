package dev.sonti.offers;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenSearchIT extends PostgresFixture {
    static final GenericContainer<?> search = SearchTestContainer.create();
    static final KafkaContainer broker = new KafkaContainer(DockerImageName.parse(
            "apache/kafka@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0").asCompatibleSubstituteFor("apache/kafka"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(9092))));
    private final JsonMapper json = JsonMapper.builder().build();
    private OpenSearchIndex index;
    private String indexName;
    private PostgresCatalog catalog;

    @BeforeAll static void startDependencies() throws Exception {
        search.start();
        broker.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(KafkaOfferSink.TOPIC, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
    }
    @AfterAll static void stopDependencies() { broker.stop(); search.stop(); }
    @BeforeEach void reset() {
        sql.execute("TRUNCATE offer_key,offer_head,offer_version,outbox,outbox_replay,index_quarantine CASCADE");
        indexName = "offers-" + UUID.randomUUID();
        index = new OpenSearchIndex(endpoint(), indexName, json);
        index.create();
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
    }
    @AfterEach void closeIndex() { index.close(); }
    private static String endpoint() { return "http://127.0.0.1:" + search.getMappedPort(9200); }
    private Offer offer(String tenant, long version, long price, int quantity, boolean deleted) {
        return new Offer(tenant, "merchant", "keyboard", version, "Mechanical keyboard", price, "USD", quantity, Instant.now(), deleted);
    }
    private List<Offer> candidates() { index.refresh(); return index.candidates("demo", "keyboard", 10); }

    @Test void externalVersionsRejectDuplicatesOlderEventsAndResurrection() {
        Offer first = offer("demo", 1, 999, 4, false);
        Offer newer = offer("demo", 3, 1299, 4, false);
        assertThat(index.project(newer)).isTrue();
        assertThat(index.project(newer)).isFalse();
        assertThat(index.project(first)).isFalse();
        assertThat(index.project(offer("demo", 3, 1, 4, false))).isFalse();
        assertThat(candidates()).containsExactly(newer);
        assertThat(index.project(offer("demo", 4, 1299, 0, true))).isTrue();
        assertThat(index.project(newer)).isFalse();
        assertThat(candidates()).isEmpty();
    }
    @Test void tenantScopedSearchAndPostgresRejectPriceStockAndDeletionLag() {
        Offer current = offer("demo", 1, 999, 4, false);
        catalog.ingest(current);
        index.project(current);
        index.project(offer("other", 1, 1, 4, false));
        assertThat(candidates()).containsExactly(current);
        var service = new OfferSearch(index::candidates, catalog, Clock.systemUTC());
        assertThat(service.search("demo", "keyboard", 10).results()).hasSize(1);
        for (Offer update : List.of(offer("demo", 2, 1299, 4, false), offer("demo", 3, 1299, 0, false),
                offer("demo", 4, 1299, 4, true))) {
            catalog.ingest(update); // Deliberately leave the projection behind.
            assertThat(service.search("demo", "keyboard", 10).results()).isEmpty();
        }
    }
    @Test void expiryBoundaryIsRejectedAgainstRealDatabase() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Offer stale = new Offer("demo", "merchant", "keyboard", 1, "Mechanical keyboard", 999, "USD", 4, now.minusSeconds(300), false);
        catalog.ingest(stale);
        index.project(stale);
        assertThat(candidates()).hasSize(1);
        assertThat(new OfferSearch(index::candidates, catalog, Clock.fixed(now, java.time.ZoneOffset.UTC))
                .search("demo", "keyboard", 10).results()).isEmpty();
    }
    @Test void malformedEnvelopeQuarantineIsDurableAndIdempotent() {
        var handler = new IndexRecordHandler(index::project, sql);
        var invalid = new ConsumerRecord<String, String>(KafkaOfferSink.TOPIC, 0, 7, "demo:merchant:keyboard", "{\"schemaVersion\":99}");
        assertThat(handler.handle(invalid)).isEqualTo("quarantined");
        assertThat(handler.handle(invalid)).isEqualTo("quarantined");
        assertThat(sql.queryForObject("SELECT count(*) FROM index_quarantine", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT payload_sha256 FROM index_quarantine", String.class)).hasSize(64);
        assertThat(candidates()).isEmpty();
    }
    @Test void missingAliasFailsWithoutAutoCreatingAnIndex() {
        try (var absent = new OpenSearchIndex(endpoint(), "missing-" + UUID.randomUUID(), json)) {
            assertThatThrownBy(() -> absent.project(offer("demo", 1, 999, 4, false)))
                    .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.status()).isEqualTo(503));
            assertThatThrownBy(() -> absent.candidates("demo", "keyboard", 10)).isInstanceOf(DomainException.class);
        }
    }
    @Test void realKafkaPoisonCommitFollowsQuarantineAndDoesNotBlockNextSnapshot() throws Exception {
        String group = "poison-" + UUID.randomUUID();
        var partition = new TopicPartition(KafkaOfferSink.TOPIC, 2);
        var meters = new SimpleMeterRegistry();
        try (var consumer = consumer(group, partition, true); var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
            long initial = consumer.position(partition);
            sink.publish(new PostgresOutbox.Delivery(UUID.randomUUID(), UUID.randomUUID(), "demo:merchant:keyboard", "{}", 1));
            publish(offer("demo", 1, 999, 4, false));
            var handler = new IndexRecordHandler(index::project, sql);
            RunningApplication.awaitReady(() -> true, () -> {
                IndexConsumer.step(consumer, handler, meters);
                return consumer.committed(java.util.Set.of(partition)).get(partition).offset() == initial + 2;
            }, Duration.ofSeconds(20));
            assertThat(sql.queryForObject("SELECT count(*) FROM index_quarantine", Integer.class)).isEqualTo(1);
            assertThat(candidates()).hasSize(1);
        } finally { meters.close(); }
    }
    @Test void packagedPipelineSearchValidationAndIndexOutage() throws Exception {
        String tenant = "pipeline-" + UUID.randomUUID();
        try (var app = new RunningApplication("--offers.publisher.enabled=true",
                "--offers.publisher.bootstrap-servers=" + broker.getBootstrapServers(), "--offers.search.enabled=true",
                "--offers.search.endpoint=" + endpoint(), "--offers.search.index=" + indexName,
                "--offers.indexer.enabled=true", "--offers.indexer.bootstrap-servers=" + broker.getBootstrapServers(),
                "--offers.indexer.group=check-" + UUID.randomUUID())) {
            app.request("PUT", "/api/v1/offers", offer(tenant, 1, 999, 4, false), 200);
            RunningApplication.awaitReady(() -> true, () -> {
                index.refresh(); return !index.candidates(tenant, "keyboard", 10).isEmpty();
            }, Duration.ofSeconds(30));
            assertThat(app.request("GET", "/api/v1/search?tenantId=" + tenant + "&q=keyboard", null, 200).path("results").size()).isEqualTo(1);
            app.request("GET", "/api/v1/search?tenantId=demo&q=keyboard&limit=51", null, 400);
            app.request("GET", "/api/v1/search?tenantId=demo&q=keyboard&cursor=invalid", null, 400);
            assertThat(app.request("GET", "/api/v1/search?tenantId=other&q=keyboard", null, 200).path("results").size()).isZero();
            search.getDockerClient().pauseContainerCmd(search.getContainerId()).exec();
            try { app.request("GET", "/api/v1/search?tenantId=demo&q=keyboard", null, 503); }
            finally { search.getDockerClient().unpauseContainerCmd(search.getContainerId()).exec(); }
        }
    }

    private KafkaConsumer<String, String> consumer(String group, TopicPartition partition, boolean initialize) {
        var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", broker.getBootstrapServers(),
                "group.id", group, "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class,
                "enable.auto.commit", false, "auto.offset.reset", "earliest", "max.poll.records", 1, "default.api.timeout.ms", 10000));
        consumer.assign(List.of(partition));
        if (initialize) {
            consumer.seekToEnd(List.of(partition));
            consumer.commitSync(Map.of(partition, new OffsetAndMetadata(consumer.position(partition))));
        }
        return consumer;
    }
    private void publish(Offer offer) {
        catalog.ingest(offer);
        try (var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
            assertThat(new OutboxRelay(new PostgresOutbox(sql, new JdbcTransactionManager(pool)), sink).publishNext())
                    .isEqualTo(OutboxRelay.Result.PUBLISHED);
        }
    }
    @Test void realKafkaReplayAfterProjectionCommitGapAndTransientIndexFailure() throws Exception {
        String group = "replay-" + UUID.randomUUID();
        // Producer partitioning for this key is resolved from Kafka's actual partitioner hash.
        String key = "demo:merchant:keyboard";
        int partitionId = org.apache.kafka.common.utils.Utils.toPositive(org.apache.kafka.common.utils.Utils.murmur2(
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8))) % 3;
        var partition = new TopicPartition(KafkaOfferSink.TOPIC, partitionId);
        var meters = new SimpleMeterRegistry();
        try {
            long initial;
            try (var consumer = consumer(group, partition, true)) {
                initial = consumer.position(partition);
                publish(offer("demo", 1, 999, 4, false));
                var crashing = new IndexRecordHandler(offer -> { index.project(offer); throw new SimulatedCrash(); }, sql);
                assertThatThrownBy(() -> {
                    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    while (System.nanoTime() < deadline) IndexConsumer.step(consumer, crashing, meters);
                }).isInstanceOf(SimulatedCrash.class);
                assertThat(consumer.committed(java.util.Set.of(partition)).get(partition).offset()).isEqualTo(initial);
            }
            assertThat(candidates()).hasSize(1);
            try (var consumer = consumer(group, partition, false)) {
                var handler = new IndexRecordHandler(index::project, sql);
                RunningApplication.awaitReady(() -> true, () -> {
                    IndexConsumer.step(consumer, handler, meters);
                    return consumer.committed(java.util.Set.of(partition)).get(partition).offset() == initial + 1;
                }, Duration.ofSeconds(20));
                assertThat(candidates()).hasSize(1);
                publish(offer("demo", 2, 1299, 4, false));
                var fail = new IndexRecordHandler(offer -> { throw new DomainException(503, "Injected outage"); }, sql);
                assertThatThrownBy(() -> {
                    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    while (System.nanoTime() < deadline) IndexConsumer.step(consumer, fail, meters);
                }).isInstanceOf(DomainException.class);
                assertThat(consumer.position(partition)).isEqualTo(initial + 1);
                assertThat(consumer.committed(java.util.Set.of(partition)).get(partition).offset()).isEqualTo(initial + 1);
                IndexConsumer.step(consumer, handler, meters);
                assertThat(candidates().getFirst().version()).isEqualTo(2);
            }
        } finally { meters.close(); }
    }
    private static final class SimulatedCrash extends Error {}
}
