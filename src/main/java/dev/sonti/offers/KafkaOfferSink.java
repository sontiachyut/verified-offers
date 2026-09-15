package dev.sonti.offers;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/** The synchronous return boundary represents a broker acknowledgement. */
public final class KafkaOfferSink implements OutboxRelay.AcknowledgingSink, AutoCloseable {
    public static final String TOPIC = "offers.v1";
    private final Producer<String, String> producer;

    public KafkaOfferSink(String bootstrapServers) {
        this(new KafkaProducer<>(producerProperties(bootstrapServers)));
    }

    KafkaOfferSink(Producer<String, String> producer) {
        this.producer = Objects.requireNonNull(producer);
    }

    static Map<String, Object> producerProperties(String bootstrapServers) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalArgumentException("An explicit Kafka bootstrap address is required.");
        }
        return Map.ofEntries(
                Map.entry("bootstrap.servers", bootstrapServers),
                Map.entry("client.id", "verified-offers-publisher"),
                Map.entry("key.serializer", StringSerializer.class),
                Map.entry("value.serializer", StringSerializer.class),
                Map.entry("enable.idempotence", true),
                Map.entry("acks", "all"),
                Map.entry("max.in.flight.requests.per.connection", 1),
                Map.entry("max.block.ms", 5000),
                Map.entry("delivery.timeout.ms", 10000),
                Map.entry("request.timeout.ms", 5000),
                Map.entry("linger.ms", 0),
                Map.entry("buffer.memory", 4 * 1024 * 1024),
                Map.entry("max.request.size", 64 * 1024));
    }

    @Override public void publish(PostgresOutbox.Delivery event) throws Exception {
        producer.send(new ProducerRecord<>(TOPIC, event.key(), event.payload())).get(12, TimeUnit.SECONDS);
    }

    @Override public void close() { producer.close(Duration.ofSeconds(5)); }
}
