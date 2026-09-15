package dev.sonti.offers;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class KafkaDeliveryIT extends PostgresFixture {
    static final KafkaContainer broker = new KafkaContainer(org.testcontainers.utility.DockerImageName.parse(
            "apache/kafka@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0")
            .asCompatibleSubstituteFor("apache/kafka"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(9092))));
    private PostgresOutbox outbox;
    private PostgresCatalog catalog;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll static void startBroker() throws Exception {
        broker.start();
        try (var admin = admin()) {
            admin.createTopics(List.of(new NewTopic(KafkaOfferSink.TOPIC, 3, (short) 1)
                    .configs(Map.of("retention.ms", "604800000"))))
                    .all().get(20, TimeUnit.SECONDS);
        }
    }
    @AfterAll static void stopBroker() { broker.stop(); }
    private static Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers(),
                "default.api.timeout.ms", 15000, "request.timeout.ms", 5000));
    }
    @BeforeEach void reset() {
        sql.execute("TRUNCATE offer_key,offer_head,offer_version,outbox,outbox_replay CASCADE");
        outbox = new PostgresOutbox(sql, new JdbcTransactionManager(pool));
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
    }
    private Offer offer(long version, boolean deleted) {
        return new Offer("tenant", "merchant", "item", version, "Headphones", 1000, "USD", 5,
                Instant.parse("2026-01-01T00:00:00Z"), deleted);
    }
    private KafkaConsumer<String, String> consumerAtEnd() {
        var consumer = new KafkaConsumer<String, String>(Map.of("bootstrap.servers", broker.getBootstrapServers(),
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class,
                "enable.auto.commit", false, "default.api.timeout.ms", 15000));
        var partitions = consumer.partitionsFor(KafkaOfferSink.TOPIC).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
        consumer.assign(partitions);
        consumer.seekToEnd(partitions);
        partitions.forEach(consumer::position); // Resolve end offsets before the test publishes.
        return consumer;
    }
    private List<ConsumerRecord<String, String>> read(KafkaConsumer<String, String> consumer, int count) {
        var records = new ArrayList<ConsumerRecord<String, String>>();
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (records.size() < count && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        assertThat(records.size()).isGreaterThanOrEqualTo(count);
        return records;
    }

    @Test void brokerAcknowledgesUnchangedUpdatesAndTombstonesOnSamePartition() {
        try (var consumer = consumerAtEnd(); var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
            catalog.ingest(offer(1, false));
            catalog.ingest(offer(2, true));
            var relay = new OutboxRelay(outbox, sink);
            assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
            assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
            var records = read(consumer, 2);
            assertThat(records).hasSize(2);
            assertThat(records).extracting(ConsumerRecord::key).containsOnly("tenant:merchant:item");
            assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
            for (var record : records) {
                var envelope = json.readTree(record.value());
                assertThat(record.value()).isEqualTo(sql.queryForObject("SELECT payload::text FROM outbox WHERE event_id=?",
                        String.class, UUID.fromString(envelope.get("eventId").asString())));
            }
            assertThat(json.readTree(records.getLast().value()).get("eventType").asString()).isEqualTo("OfferDeleted");
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class)).isEqualTo(2);
        }
    }

    @Test void acceptedKafkaRecordReplaysAfterCrashBeforeDatabaseAcknowledgement() {
        try (var consumer = consumerAtEnd(); var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
            catalog.ingest(offer(1, false));
            var crashing = new OutboxRelay(outbox, event -> { sink.publish(event); throw new SimulatedCrash(); });
            assertThatThrownBy(crashing::publishNext).isInstanceOf(SimulatedCrash.class);
            var first = read(consumer, 1).getFirst();
            assertThat(sql.queryForObject("SELECT published_at IS NULL FROM outbox", Boolean.class)).isTrue();
            sql.update("UPDATE outbox SET lease_until=statement_timestamp()-interval '1 second'");
            try (var restarted = new KafkaOfferSink(broker.getBootstrapServers())) {
                assertThat(new OutboxRelay(outbox, restarted).publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
            }
            var replay = read(consumer, 1).getFirst();
            assertThat(replay.key()).isEqualTo(first.key());
            assertThat(replay.value()).isEqualTo(first.value());
            assertThat(replay.offset()).isGreaterThan(first.offset());
            assertThat(sql.queryForObject("SELECT count(*) FROM offer_version", Integer.class)).isEqualTo(1);
        }
    }

    @Test void pausedBrokerLeavesEventUnpublishedThenRecovers() throws Exception {
        try (var consumer = consumerAtEnd(); var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
            catalog.ingest(offer(1, false));
            var relay = new OutboxRelay(outbox, sink);
            assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
            read(consumer, 1);
            catalog.ingest(offer(2, false));
            broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
            try {
                assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.RETRY_OR_QUARANTINED);
                assertThat(sql.queryForObject("SELECT published_at IS NULL AND lease_token IS NULL FROM outbox WHERE aggregate_version=2",
                        Boolean.class)).isTrue();
            } finally { broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec(); }
            try (var admin = admin()) { admin.describeCluster().nodes().get(15, TimeUnit.SECONDS); }
            sql.update("UPDATE outbox SET next_attempt_at=statement_timestamp()-interval '1 second' WHERE published_at IS NULL");
            assertThat(relay.publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
            var recovered = read(consumer, 1);
            assertThat(recovered).allSatisfy(record ->
                    assertThat(json.readTree(record.value()).get("aggregateVersion").asLong()).isEqualTo(2));
        }
    }

    @Test void packagedApiPublishesWhenExplicitlyEnabledAndStopsGracefully() throws Exception {
        try (var consumer = consumerAtEnd(); var app = new RunningApplication(
                "--offers.publisher.enabled=true", "--offers.publisher.bootstrap-servers=" + broker.getBootstrapServers())) {
            app.request("PUT", "/api/v1/offers", offer(1, false), 200);
            var record = read(consumer, 1).getFirst();
            assertThat(record.key()).isEqualTo("tenant:merchant:item");
            RunningApplication.awaitReady(() -> true,
                    () -> sql.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class) == 1,
                    Duration.ofSeconds(5));
        }
    }

    @Test void apiRemainsAvailableAndShutsDownDuringBrokerOutage() throws Exception {
        try (var consumer = consumerAtEnd()) {
            broker.getDockerClient().pauseContainerCmd(broker.getContainerId()).exec();
            try {
                try (var app = new RunningApplication("--offers.publisher.enabled=true",
                        "--offers.publisher.bootstrap-servers=" + broker.getBootstrapServers())) {
                    app.request("PUT", "/api/v1/offers", offer(1, false), 200);
                    RunningApplication.awaitReady(() -> true,
                            () -> sql.queryForObject("SELECT count(*) FROM outbox WHERE lease_token IS NOT NULL", Integer.class) == 1,
                            Duration.ofSeconds(5));
                    assertThat(app.request("GET", "/api/v1/offers/tenant/merchant/item", null, 200)
                            .get("version").asLong()).isEqualTo(1);
                } // The process helper fails if shutdown needs a force kill.
                assertThat(sql.queryForObject("SELECT published_at IS NULL AND lease_token IS NULL FROM outbox", Boolean.class)).isTrue();
            } finally { broker.getDockerClient().unpauseContainerCmd(broker.getContainerId()).exec(); }
            try (var admin = admin()) { admin.describeCluster().nodes().get(15, TimeUnit.SECONDS); }
            sql.update("UPDATE outbox SET next_attempt_at=statement_timestamp()-interval '1 second'");
            try (var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
                assertThat(new OutboxRelay(outbox, sink).publishNext()).isEqualTo(OutboxRelay.Result.PUBLISHED);
            }
            assertThat(read(consumer, 1)).hasSize(1);
        }
    }

    private static final class SimulatedCrash extends Error {}
}
