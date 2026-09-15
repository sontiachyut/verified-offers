package dev.sonti.offers;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

/** The worker thread alone polls, commits, seeks and closes the Kafka consumer. */
final class IndexConsumer implements AutoCloseable {
    private final Consumer<String, String> consumer;
    private final IndexRecordHandler handler;
    private final MeterRegistry meters;
    private volatile boolean running;
    private Thread worker;

    IndexConsumer(String servers, String group, IndexRecordHandler handler, MeterRegistry meters) {
        if (servers == null || servers.isBlank() || group == null || !group.matches("[a-zA-Z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("Explicit bootstrap servers and consumer group required.");
        }
        consumer = new KafkaConsumer<>(Map.ofEntries(Map.entry("bootstrap.servers", servers),
                Map.entry("group.id", group), Map.entry("enable.auto.commit", false),
                Map.entry("auto.offset.reset", "earliest"), Map.entry("allow.auto.create.topics", false),
                Map.entry("key.deserializer", StringDeserializer.class), Map.entry("value.deserializer", StringDeserializer.class),
                Map.entry("max.poll.records", 1), Map.entry("max.partition.fetch.bytes", 65536),
                Map.entry("fetch.max.bytes", 262144), Map.entry("default.api.timeout.ms", 5000),
                Map.entry("request.timeout.ms", 5000)));
        this.handler = handler;
        this.meters = meters;
    }

    public synchronized void start() {
        if (worker != null) throw new IllegalStateException("Worker already started.");
        running = true;
        worker = new Thread(this::run, "offers-indexer");
        worker.start();
    }
    private void run() {
        try {
            consumer.subscribe(List.of(KafkaOfferSink.TOPIC));
            while (running) {
                try { step(consumer, handler, meters); }
                catch (WakeupException stop) { if (running) throw stop; }
                catch (RuntimeException failure) {
                    meters.counter("offers.indexer.records", "result", "retry").increment();
                    try { Thread.sleep(1000); }
                    catch (InterruptedException stop) { Thread.currentThread().interrupt(); break; }
                }
            }
        } finally { running = false; consumer.close(Duration.ofSeconds(5)); }
    }
    static void step(Consumer<String, String> consumer, IndexRecordHandler handler, MeterRegistry meters) {
        var records = consumer.poll(Duration.ofMillis(250));
        for (var record : records) {
            var partition = new TopicPartition(record.topic(), record.partition());
            try {
                String result = handler.handle(record);
                consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)), Duration.ofSeconds(5));
                meters.counter("offers.indexer.records", "result", result).increment();
            } catch (RuntimeException failed) {
                // max.poll.records=1: nothing else from this poll can be skipped.
                consumer.seek(partition, record.offset());
                throw failed;
            }
        }
    }
    @Override public synchronized void close() {
        running = false;
        if (worker == null) { consumer.close(Duration.ofSeconds(5)); return; }
        consumer.wakeup();
        try { worker.join(25000); }
        catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted shutdown."); }
        if (worker.isAlive()) throw new IllegalStateException("Indexer shutdown deadline exceeded.");
    }
}
