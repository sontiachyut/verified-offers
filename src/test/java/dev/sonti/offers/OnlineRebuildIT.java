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
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OnlineRebuildIT extends PostgresFixture {
    static final GenericContainer<?> search = SearchTestContainer.create();
    static final KafkaContainer broker = new KafkaContainer(DockerImageName.parse(
            "apache/kafka@sha256:9916d60eca5d599550e2c320230808fda342124ba550bb4ac4ea8591803262a0").asCompatibleSubstituteFor("apache/kafka"))
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false").withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(9092))));
    private final JsonMapper json = JsonMapper.builder().build();
    private OnlineRebuildStore store;
    private PostgresCatalog catalog;
    private String tenant;
    private String alias;
    @BeforeAll static void startDependencies() throws Exception {
        search.start(); broker.start();
        try (var admin = admin()) { admin.createTopics(List.of(new NewTopic(KafkaOfferSink.TOPIC, 3, (short) 1))).all().get(20, TimeUnit.SECONDS); }
    }
    @AfterAll static void stopDependencies() { broker.stop(); search.stop(); }
    private static Admin admin() { return Admin.create(Map.of("bootstrap.servers", broker.getBootstrapServers(),
            "default.api.timeout.ms", 5000, "request.timeout.ms", 5000)); }
    private String endpoint() { return "http://127.0.0.1:" + search.getMappedPort(9200); }
    @BeforeEach void reset() {
        sql.execute("TRUNCATE index_rebuild,offer_key,offer_head,offer_version,outbox,outbox_replay,index_quarantine CASCADE");
        store = new OnlineRebuildStore(sql, new JdbcTransactionManager(pool), json);
        catalog = new PostgresCatalog(sql, transactions, Clock.systemUTC(), Duration.ofMinutes(5), json);
        tenant = "tenant-" + UUID.randomUUID(); alias = "live-" + UUID.randomUUID();
        new IndexingGate(sql, alias);
        try (var live = live()) { live.create(); }
    }
    private OpenSearchIndex live() { return new OpenSearchIndex(endpoint(), alias, json); }
    private Offer offer(String id, long version, boolean deleted) {
        return new Offer(tenant, "merchant", id, version, "Mechanical keyboard", 999 + version, "USD", 4, Instant.now(), deleted);
    }
    private void send(long version, String id) throws Exception {
        var row = sql.queryForMap("SELECT event_id,payload::text FROM outbox WHERE tenant_id=? AND aggregate_id=? AND aggregate_version=?",
                tenant, "merchant:" + id, version);
        try (var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
            sink.publish(new PostgresOutbox.Delivery((UUID) row.get("event_id"), UUID.randomUUID(),
                    tenant + ":merchant:" + id, (String) row.get("payload"), 1));
        }
    }
    private void until(OnlineRebuild runner, UUID id, String state) {
        for (int i = 0; i < 40; i++) {
            if (store.get(id).state().equals(state)) return;
            var result = runner.step(id);
            assertThat(result).isIn(OnlineRebuild.Result.PROGRESSED, OnlineRebuild.Result.WAITING, OnlineRebuild.Result.ACTIVE);
        }
        throw new AssertionError("Fixture did not reach " + state);
    }
    @Test void rebuildCatchesOutOfOrderEventsTombstonesAndResumesLaterUpdates() throws Exception {
        Offer first = catalog.ingest(offer("item", 1, false));
        try (var live = live(); var window = new KafkaWindow(broker.getBootstrapServers())) {
            live.project(first);
            var runner = new OnlineRebuild(store, window, json);
            var run = runner.create(endpoint(), alias);
            catalog.ingest(offer("item", 2, false));
            Offer deletion = catalog.ingest(offer("item", 3, true));
            Offer added = catalog.ingest(offer("new", 1, false));
            send(3, "item"); send(2, "item"); send(3, "item"); send(1, "new");
            assertThat(runner.step(run.id())).isEqualTo(OnlineRebuild.Result.PROGRESSED); // Durable pause.
            assertThatThrownBy(() -> new IndexingGate(sql, alias).run()).isInstanceOf(DomainException.class);
            assertThat(runner.step(run.id())).isEqualTo(OnlineRebuild.Result.PROGRESSED); // Fixed end.
            Offer afterEnd = catalog.ingest(offer("new", 2, false)); send(2, "new");
            until(runner, run.id(), "SWITCHING");
            assertThat(live.liveTarget()).isEqualTo(run.oldIndex());
            var candidate = store.snapshots.get(store.get(run.id()).candidate());
            try (var target = new OpenSearchIndex(endpoint(), candidate.shadowAlias(), json)) {
                assertThat(target.matches(List.of(deletion, added))).isTrue();
                assertThat(target.matches(List.of(afterEnd))).isFalse(); // Outside the sealed window.
            }
            until(runner, run.id(), "ACTIVE");
            assertThat(live.liveTarget()).isEqualTo(candidate.shadowAlias() + "-v1");
            assertThatCode(() -> new IndexingGate(sql, alias).run()).doesNotThrowAnyException();
            // Resume a consumer at the exact sealed boundary; the later update is still available.
            var end = store.get(run.id()).end();
            try (var consumer = manualConsumer()) {
                var parts = end.offsets().stream().map(o -> new TopicPartition(KafkaOfferSink.TOPIC, o.partition())).toList();
                consumer.assign(parts); end.offsets().forEach(o -> consumer.seek(new TopicPartition(KafkaOfferSink.TOPIC, o.partition()), o.end()));
                var meters = new SimpleMeterRegistry();
                try {
                    var handler = new IndexRecordHandler(live::project, sql, new IndexingGate(sql, alias));
                    RunningApplication.awaitReady(() -> true, () -> {
                        IndexConsumer.step(consumer, handler, meters); live.refresh();
                        return live.candidates(tenant, "keyboard", 10).stream().anyMatch(o -> o.equals(afterEnd));
                    }, Duration.ofSeconds(15));
                    assertThat(live.candidates(tenant, "keyboard", 10)).containsExactly(afterEnd);
                } finally { meters.close(); }
            }
            assertThatThrownBy(() -> runner.abort(run.id())).isInstanceOf(DomainException.class);
        }
    }
    private KafkaConsumer<String, String> manualConsumer() {
        return new KafkaConsumer<>(Map.of("bootstrap.servers", broker.getBootstrapServers(), "group.id", "check-" + UUID.randomUUID(),
                "enable.auto.commit", false, "auto.offset.reset", "none", "max.poll.records", 1,
                "key.deserializer", StringDeserializer.class, "value.deserializer", StringDeserializer.class));
    }
    @Test void packagedCommandsResumeHandoffAcrossProcessesAndKeepWorkersDisabled() throws Exception {
        catalog.ingest(offer("item", 1, false));
        var created = command("--online=begin", "--endpoint=" + endpoint(), "--alias=" + alias,
                "--bootstrap-servers=" + broker.getBootstrapServers(), "--ack-upgraded=true");
        UUID id = UUID.fromString(created.path("run").path("id").asString());
        Offer updated = catalog.ingest(offer("item", 2, false)); send(2, "item");
        for (int i = 0; i < 20 && !store.get(id).state().equals("ACTIVE"); i++) {
            command("--online=step", "--run=" + id, "--bootstrap-servers=" + broker.getBootstrapServers());
        }
        assertThat(command("--online=status", "--run=" + id).path("run").path("state").asString()).isEqualTo("ACTIVE");
        try (var live = live()) { live.refresh(); assertThat(live.candidates(tenant, "keyboard", 10)).containsExactly(updated); }
        assertThat(sql.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class)).isZero();
        assertThatCode(() -> new IndexingGate(sql, alias).run()).doesNotThrowAnyException();
        var another = command("--online=begin", "--endpoint=" + endpoint(), "--alias=" + alias,
                "--bootstrap-servers=" + broker.getBootstrapServers(), "--ack-upgraded=true");
        String anotherId = another.path("run").path("id").asString();
        assertThat(command("--online=abort", "--run=" + anotherId).path("result").asString()).isEqualTo("ABORTED");
    }
    private tools.jackson.databind.JsonNode command(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of(System.getProperty("java.home") + "/bin/java", "-jar",
                "target/verified-offers-0.1.0-SNAPSHOT.jar"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("APP_DATABASE_URL", postgres.getJdbcUrl());
        builder.environment().put("APP_DATABASE_USER", postgres.getUsername());
        builder.environment().put("APP_DATABASE_PASSWORD", postgres.getPassword());
        builder.environment().put("OFFERS_PUBLISHER_ENABLED", "true");
        builder.environment().put("OFFERS_INDEXER_ENABLED", "true");
        builder.environment().put("OFFERS_SEARCH_ENABLED", "true");
        builder.environment().put("OFFERS_FEEDS_ENABLED", "true");
        builder.environment().put("OFFERS_FEEDS_WORKER_ENABLED", "true");
        var process = builder.start();
        try {
            assertThat(process.waitFor(25, TimeUnit.SECONDS)).as("one-shot process exits itself").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            assertThat(output).doesNotContain("Tomcat", "Kafka version:", "Exception:");
            return output.lines().filter(line -> line.startsWith("{")).map(json::readTree).findFirst().orElseThrow();
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    @Test void consumerRewindsPausedRecordsWithoutProjectionOrOffsetCommit() throws Exception {
        try (var consumer = manualConsumer(); var window = new KafkaWindow(broker.getBootstrapServers()); var live = live()) {
            var ends = window.capture();
            consumer.assign(ends.offsets().stream().map(o -> new TopicPartition(KafkaOfferSink.TOPIC, o.partition())).toList());
            var initial = new java.util.HashMap<TopicPartition, OffsetAndMetadata>();
            for (var o : ends.offsets()) {
                var p = new TopicPartition(KafkaOfferSink.TOPIC, o.partition()); consumer.seek(p, o.end()); initial.put(p, new OffsetAndMetadata(o.end()));
            }
            consumer.commitSync(initial);
            var runner = new OnlineRebuild(store, window, json); var run = runner.create(endpoint(), alias);
            runner.step(run.id());
            catalog.ingest(offer("item", 1, false)); send(1, "item");
            var meters = new SimpleMeterRegistry();
            try {
                var handler = new IndexRecordHandler(live::project, sql, new IndexingGate(sql, alias));
                assertThatThrownBy(() -> {
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (System.nanoTime() < deadline) IndexConsumer.step(consumer, handler, meters);
                }).isInstanceOf(DomainException.class);
                assertThat(consumer.committed(initial.keySet())).isEqualTo(initial);
                live.refresh(); assertThat(live.candidates(tenant, "keyboard", 10)).isEmpty();
                assertThat(runner.abort(run.id())).isEqualTo(OnlineRebuild.Result.ABORTED);
                assertThat(live.liveTarget()).isEqualTo(run.oldIndex());
            } finally { meters.close(); }
        }
    }
    @Test void lostAliasAcknowledgementReconcilesForwardAfterCoordinatorRestart() {
        catalog.ingest(offer("item", 1, false));
        try (var window = new KafkaWindow(broker.getBootstrapServers()); var live = live()) {
            var runner = new OnlineRebuild(store, window, json); var run = runner.create(endpoint(), alias);
            until(runner, run.id(), "SWITCHING");
            var candidate = store.snapshots.get(store.get(run.id()).candidate());
            // Exact crash boundary: OpenSearch changed, PostgreSQL still says SWITCHING.
            try (var target = new OpenSearchIndex(endpoint(), candidate.shadowAlias(), json)) { live.promote(run.oldIndex(), target, candidate.id()); }
            assertThat(store.get(run.id()).state()).isEqualTo("SWITCHING");
            assertThatThrownBy(() -> runner.abort(run.id())).isInstanceOf(DomainException.class);
            var restarted = new OnlineRebuild(new OnlineRebuildStore(sql, new JdbcTransactionManager(pool), json), window, json);
            assertThat(restarted.step(run.id())).isEqualTo(OnlineRebuild.Result.ACTIVE);
            assertThat(live.liveTarget()).isEqualTo(candidate.shadowAlias() + "-v1");
            assertThatCode(() -> new IndexingGate(sql, alias).run()).doesNotThrowAnyException();
        }
    }
    @Test void poisonedWindowDoesNotAdvanceAndAbortRestoresOldRoute() throws Exception {
        try (var window = new KafkaWindow(broker.getBootstrapServers()); var live = live()) {
            var runner = new OnlineRebuild(store, window, json); var run = runner.create(endpoint(), alias);
            try (var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
                sink.publish(new PostgresOutbox.Delivery(UUID.randomUUID(), UUID.randomUUID(), tenant + ":merchant:bad", "{}", 1));
            }
            runner.step(run.id()); runner.step(run.id());
            var before = store.cursors(run.id());
            assertThat(runner.step(run.id())).isEqualTo(OnlineRebuild.Result.RETRY);
            assertThat(store.cursors(run.id())).isEqualTo(before);
            assertThat(store.get(run.id()).lastError()).isEqualTo("EVENT_INVALID");
            assertThat(live.liveTarget()).isEqualTo(run.oldIndex());
            assertThat(runner.abort(run.id())).isEqualTo(OnlineRebuild.Result.ABORTED);
            assertThatCode(() -> new IndexingGate(sql, alias).run()).doesNotThrowAnyException();
        }
    }
    @Test void deletedKafkaRangeIsRejectedBeforeSealing() throws Exception {
        try (var window = new KafkaWindow(broker.getBootstrapServers())) {
            var runner = new OnlineRebuild(store, window, json); var run = runner.create(endpoint(), alias);
            catalog.ingest(offer("item", 1, false)); send(1, "item");
            var current = window.capture();
            var changed = current.offsets().stream().filter(o -> o.end() > run.start().offsets().get(o.partition()).end()).findFirst().orElseThrow();
            try (var admin = admin()) {
                admin.deleteRecords(Map.of(new TopicPartition(KafkaOfferSink.TOPIC, changed.partition()), RecordsToDelete.beforeOffset(changed.end())))
                        .all().get(10, TimeUnit.SECONDS);
            }
            runner.step(run.id());
            assertThat(runner.step(run.id())).isEqualTo(OnlineRebuild.Result.RETRY);
            assertThat(store.get(run.id()).lastError()).isEqualTo("WINDOW_INVALID");
            assertThat(store.get(run.id()).state()).isEqualTo("PAUSED");
            assertThat(runner.abort(run.id())).isEqualTo(OnlineRebuild.Result.ABORTED);
        }
    }

    @Test void delayedPrePauseProjectionAndAcknowledgementCannotSkipPostWindowUpdate() throws Exception {
        try (var window = new KafkaWindow(broker.getBootstrapServers()); var live = live(); var consumer = manualConsumer();
                var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var runner = new OnlineRebuild(store, window, json); var run = runner.create(endpoint(), alias);
            consumer.assign(run.start().offsets().stream().map(o -> new TopicPartition(KafkaOfferSink.TOPIC, o.partition())).toList());
            run.start().offsets().forEach(o -> consumer.seek(new TopicPartition(KafkaOfferSink.TOPIC, o.partition()), o.end()));
            catalog.ingest(offer("item", 1, false)); send(1, "item");
            var passedGate = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var gate = new IndexingGate(sql, alias);
            var handler = new IndexRecordHandler(live::project, sql, () -> {
                gate.run(); passedGate.countDown();
                try { if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Test release deadline"); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException(stopped); }
            });
            var meters = new SimpleMeterRegistry();
            try {
                var pending = executor.submit(() -> {
                    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                    while (passedGate.getCount() != 0 && System.nanoTime() < deadline) IndexConsumer.step(consumer, handler, meters);
                });
                try {
                    assertThat(passedGate.await(10, TimeUnit.SECONDS)).isTrue();
                    runner.step(run.id()); runner.step(run.id()); // Pause and seal AFTER that record passed the gate.
                    Offer newer = catalog.ingest(offer("item", 2, false)); send(2, "item");
                    until(runner, run.id(), "ACTIVE");
                    release.countDown(); pending.get(10, TimeUnit.SECONDS); // Delayed old write/commit now finishes.
                    RunningApplication.awaitReady(() -> true, () -> {
                        IndexConsumer.step(consumer, new IndexRecordHandler(live::project, sql, gate), meters);
                        live.refresh(); return live.candidates(tenant, "keyboard", 10).contains(newer);
                    }, Duration.ofSeconds(10));
                    assertThat(live.candidates(tenant, "keyboard", 10)).containsExactly(newer);
                } finally { release.countDown(); }
            } finally { meters.close(); }
        }
    }
    @Test void validLookingButForgedEventRollsBackWholeReplayBatch() throws Exception {
        try (var window = new KafkaWindow(broker.getBootstrapServers())) {
            var runner = new OnlineRebuild(store, window, json); var run = runner.create(endpoint(), alias);
            catalog.ingest(offer("item", 1, false)); send(1, "item");
            var envelope = (tools.jackson.databind.node.ObjectNode) json.readTree(sql.queryForObject(
                    "SELECT payload::text FROM outbox WHERE tenant_id=?", String.class, tenant));
            ((tools.jackson.databind.node.ObjectNode) envelope.get("payload")).put("priceMinor", 42);
            try (var sink = new KafkaOfferSink(broker.getBootstrapServers())) {
                sink.publish(new PostgresOutbox.Delivery(UUID.randomUUID(), UUID.randomUUID(), tenant + ":merchant:item",
                        json.writeValueAsString(envelope), 1));
            }
            runner.step(run.id()); runner.step(run.id());
            var before = store.cursors(run.id());
            assertThat(runner.step(run.id())).isEqualTo(OnlineRebuild.Result.RETRY);
            assertThat(store.cursors(run.id())).isEqualTo(before);
            assertThat(sql.queryForObject("SELECT count(*) FROM index_online_expected WHERE run_id=?", Integer.class, run.id())).isZero();
            assertThat(store.get(run.id()).lastError()).isEqualTo("EVENT_INVALID");
            assertThat(runner.abort(run.id())).isEqualTo(OnlineRebuild.Result.ABORTED);
        }
    }
    @Test void coordinatorLeaseFencesExpiredOwner() {
        try (var window = new KafkaWindow(broker.getBootstrapServers())) {
            var runner = new OnlineRebuild(store, window, json); var run = runner.create(endpoint(), alias);
            var old = store.claim(run.id()).orElseThrow();
            assertThat(store.claim(run.id())).isEmpty();
            sql.update("UPDATE index_online_run SET lease_until=statement_timestamp()-interval '1 second' WHERE run_id=?", run.id());
            var current = store.claim(run.id()).orElseThrow();
            assertThatThrownBy(() -> store.pause(old)).isInstanceOf(DomainException.class);
            store.release(old, "STEP_FAILED");
            assertThat(store.claim(run.id())).isEmpty();
            store.pause(current); store.release(current, null);
            assertThat(store.get(run.id()).state()).isEqualTo("PAUSED");
            assertThat(runner.abort(run.id())).isEqualTo(OnlineRebuild.Result.ABORTED);
        }
    }
    @Test void topicRecreationAndPartitionGrowthInvalidateCapturedBoundary() throws Exception {
        try (var window = new KafkaWindow(broker.getBootstrapServers()); var admin = admin()) {
            var runner = new OnlineRebuild(store, window, json); var run = runner.create(endpoint(), alias);
            admin.createPartitions(Map.of(KafkaOfferSink.TOPIC, org.apache.kafka.clients.admin.NewPartitions.increaseTo(4)))
                    .all().get(10, TimeUnit.SECONDS);
            awaitTopicMetadata(admin, 4, null);
            runner.step(run.id());
            assertThat(runner.step(run.id())).isEqualTo(OnlineRebuild.Result.RETRY);
            assertThat(store.get(run.id()).lastError()).isEqualTo("WINDOW_INVALID");
            runner.abort(run.id());
            var second = runner.create(endpoint(), alias);
            admin.deleteTopics(List.of(KafkaOfferSink.TOPIC)).all().get(10, TimeUnit.SECONDS);
            admin.createTopics(List.of(new NewTopic(KafkaOfferSink.TOPIC, 3, (short) 1))).all().get(10, TimeUnit.SECONDS);
            awaitTopicMetadata(admin, 3, second.start().topicId());
            assertThat(window.capture().topicId()).isNotEqualTo(second.start().topicId());
            runner.step(second.id());
            assertThat(runner.step(second.id())).isEqualTo(OnlineRebuild.Result.RETRY);
            assertThat(store.get(second.id()).lastError()).isEqualTo("WINDOW_INVALID");
            runner.abort(second.id());
        }
    }

    private static void awaitTopicMetadata(Admin admin, int partitions, String previousTopicId) throws Exception {
        // CreateTopics/CreatePartitions acknowledgement precedes broker metadata
        // visibility. Wait only for fixture readiness; do not retry the rebuild
        // steps or weaken their WINDOW_INVALID assertions.
        RunningApplication.awaitReady(broker::isRunning, () -> {
            try {
                var topic = admin.describeTopics(List.of(KafkaOfferSink.TOPIC)).allTopicNames()
                        .get(5, TimeUnit.SECONDS).get(KafkaOfferSink.TOPIC);
                return topic.partitions().size() == partitions
                        && !topic.topicId().toString().equals(previousTopicId)
                        && topic.partitions().stream().allMatch(partition -> partition.leader() != null
                                && partition.leader().id() >= 0 && !partition.isr().isEmpty());
            } catch (java.util.concurrent.ExecutionException unavailable) {
                if (unavailable.getCause() instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException)
                    return false;
                throw unavailable;
            }
        }, Duration.ofSeconds(20));
    }
}
