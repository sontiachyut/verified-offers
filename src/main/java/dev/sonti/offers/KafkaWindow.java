package dev.sonti.offers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Manual assignment only: never joins or commits offsets for a live consumer group. */
final class KafkaWindow implements AutoCloseable {
    record Offset(int partition, long beginning, long end) {}
    record Boundary(String topicId, List<Offset> offsets) {}
    private final Admin admin;
    private final KafkaConsumer<String, String> consumer;
    KafkaWindow(String servers) {
        if (servers == null || servers.isBlank()) throw new IllegalArgumentException("Bootstrap servers required.");
        admin = Admin.create(Map.of("bootstrap.servers", servers, "default.api.timeout.ms", 5000, "request.timeout.ms", 5000));
        consumer = new KafkaConsumer<>(Map.ofEntries(Map.entry("bootstrap.servers", servers),
                Map.entry("key.deserializer", StringDeserializer.class), Map.entry("value.deserializer", StringDeserializer.class),
                Map.entry("enable.auto.commit", false), Map.entry("auto.offset.reset", "none"),
                Map.entry("allow.auto.create.topics", false), Map.entry("max.poll.records", 100),
                Map.entry("max.partition.fetch.bytes", 65536), Map.entry("fetch.max.bytes", 262144),
                Map.entry("default.api.timeout.ms", 5000), Map.entry("request.timeout.ms", 5000)));
    }
    Boundary capture() {
        try {
            var before = admin.describeTopics(List.of(KafkaOfferSink.TOPIC)).allTopicNames().get(5, TimeUnit.SECONDS).get(KafkaOfferSink.TOPIC);
            if (before.partitions().isEmpty() || before.partitions().size() > 32) throw invalid();
            var partitions = before.partitions().stream().map(p -> new TopicPartition(KafkaOfferSink.TOPIC, p.partition()))
                    .sorted(Comparator.comparingInt(TopicPartition::partition)).toList();
            var begins = consumer.beginningOffsets(partitions);
            var ends = consumer.endOffsets(partitions);
            var after = admin.describeTopics(List.of(KafkaOfferSink.TOPIC)).allTopicNames().get(5, TimeUnit.SECONDS).get(KafkaOfferSink.TOPIC);
            if (!before.topicId().equals(after.topicId()) || !partitions.stream().map(TopicPartition::partition).toList().equals(
                    after.partitions().stream().map(p -> p.partition()).sorted().toList())) throw invalid();
            return new Boundary(before.topicId().toString(), partitions.stream()
                    .map(p -> new Offset(p.partition(), begins.get(p), ends.get(p))).toList());
        } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException("Kafka capture interrupted."); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
            throw new IllegalStateException("Kafka boundary unavailable.");
        }
    }
    static void validate(Boundary start, Boundary current) {
        if (!start.topicId().equals(current.topicId()) || start.offsets().size() != current.offsets().size()) throw invalid();
        for (int i = 0; i < start.offsets().size(); i++) {
            var a = start.offsets().get(i); var b = current.offsets().get(i);
            if (a.partition() != b.partition() || a.end() > b.end() || b.beginning() > a.end()) throw invalid();
        }
    }
    static void validateSeal(Boundary start, Boundary end) {
        validate(start, end);
        long total = 0;
        for (int i = 0; i < start.offsets().size(); i++) {
            long delta = end.offsets().get(i).end() - start.offsets().get(i).end();
            if (delta > 10000 - total) throw invalid();
            total += delta;
        }
    }
    List<ConsumerRecord<String, String>> read(Boundary sealed, int partition, long next, long end) {
        Boundary current = capture();
        if (!sealed.topicId().equals(current.topicId()) || sealed.offsets().size() != current.offsets().size()) throw invalid();
        for (int i = 0; i < sealed.offsets().size(); i++) {
            if (sealed.offsets().get(i).partition() != current.offsets().get(i).partition()
                    || current.offsets().get(i).end() < sealed.offsets().get(i).end()) throw invalid();
        }
        var observed = current.offsets().stream().filter(o -> o.partition() == partition).findFirst().orElseThrow(KafkaWindow::invalid);
        if (observed.beginning() > next || observed.end() < end || next > end) throw invalid();
        var key = new TopicPartition(KafkaOfferSink.TOPIC, partition);
        consumer.assign(List.of(key)); consumer.seek(key, next);
        var records = new ArrayList<ConsumerRecord<String, String>>();
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (records.isEmpty() && System.nanoTime() < deadline) {
            for (var record : consumer.poll(Duration.ofMillis(250))) {
                if (record.offset() >= end) break;
                if (record.offset() != next + records.size()) throw invalid();
                records.add(record);
            }
            if (records.isEmpty() && consumer.position(key) > next) throw invalid();
        }
        return List.copyOf(records);
    }
    static final class InvalidBoundary extends DomainException {
        InvalidBoundary() { super(409, "Kafka replay boundary invalid or no longer retained."); }
    }
    private static InvalidBoundary invalid() { return new InvalidBoundary(); }
    @Override public void close() { consumer.close(Duration.ofSeconds(5)); admin.close(Duration.ofSeconds(5)); }
}
