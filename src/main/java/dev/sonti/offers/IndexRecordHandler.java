package dev.sonti.offers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;

/** Returning means a durable side effect; Kafka offset commit is deliberately separate. */
final class IndexRecordHandler {
    private final Consumer<Offer> projection;
    private final JdbcTemplate sql;
    private final Runnable gate;
    IndexRecordHandler(Consumer<Offer> projection, JdbcTemplate sql) { this(projection, sql, () -> {}); }
    IndexRecordHandler(Consumer<Offer> projection, JdbcTemplate sql, Runnable gate) {
        this.projection = projection; this.sql = sql; this.gate = gate;
    }
    String handle(ConsumerRecord<String, String> record) {
        gate.run();
        Offer offer;
        try { offer = OfferEvent.parse(record.key(), record.value()); }
        catch (IllegalArgumentException invalid) {
            sql.update("""
                    INSERT INTO index_quarantine(topic,partition_id,record_offset,reason,payload_sha256)
                    VALUES (?,?,?,'INVALID_ENVELOPE',?) ON CONFLICT DO NOTHING
                    """, record.topic(), record.partition(), record.offset(), digest(record.value()));
            return "quarantined";
        }
        projection.accept(offer);
        return "projected";
    }
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
