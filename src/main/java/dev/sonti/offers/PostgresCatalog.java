package dev.sonti.offers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

public final class PostgresCatalog implements OfferCatalog {
    private final JdbcTemplate sql;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration freshness;
    private final JsonMapper json;

    public PostgresCatalog(JdbcTemplate sql, TransactionTemplate tx, Clock clock, Duration freshness, JsonMapper json) {
        this.sql = sql;
        this.tx = tx;
        this.clock = clock;
        this.freshness = freshness;
        this.json = json;
    }

    @Override public Offer ingest(Offer offer) {
        if (offer.sourceUpdatedAt().isAfter(clock.instant())) throw new IllegalArgumentException("Future source timestamp.");
        return tx.execute(status -> {
            sql.update("INSERT INTO offer_key VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    offer.tenantId(), offer.merchantId(), offer.offerId());
            sql.queryForObject("SELECT offer_id FROM offer_key WHERE tenant_id=? AND merchant_id=? AND offer_id=? FOR UPDATE",
                    String.class, offer.tenantId(), offer.merchantId(), offer.offerId());
            Offer current = find(offer.tenantId(), offer.merchantId(), offer.offerId());
            if (current != null) {
                if (offer.version() < current.version()) throw new DomainException(409, "Stale source version.");
                if (offer.version() == current.version()) {
                    if (!offer.equals(current)) throw new DomainException(409, "Same version has different facts.");
                    return current;
                }
            }
            String payload = json.writeValueAsString(offer);
            String canonical = json.writeValueAsString(List.of(offer.tenantId(), offer.merchantId(), offer.offerId(),
                    offer.version(), offer.title(), offer.priceMinor(), offer.currency(), offer.availableQuantity(),
                    offer.sourceUpdatedAt().toString(), offer.deleted()));
            sql.update("""
                    INSERT INTO offer_version(tenant_id,merchant_id,offer_id,version,price_minor,currency,
                    available_quantity,source_updated_at,deleted,payload,payload_hash)
                    VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?)
                    """, offer.tenantId(), offer.merchantId(), offer.offerId(), offer.version(), offer.priceMinor(),
                    offer.currency(), offer.availableQuantity(), Timestamp.from(offer.sourceUpdatedAt()), offer.deleted(),
                    payload, sha256(canonical));
            sql.update("""
                    INSERT INTO offer_head VALUES (?,?,?,?)
                    ON CONFLICT (tenant_id,merchant_id,offer_id) DO UPDATE SET version=EXCLUDED.version
                    """, offer.tenantId(), offer.merchantId(), offer.offerId(), offer.version());
            UUID event = UUID.randomUUID();
            String aggregate = offer.merchantId() + ":" + offer.offerId();
            String type = offer.deleted() ? "OfferDeleted" : "OfferUpdated";
            String envelope = json.writeValueAsString(Map.of("eventId", event, "eventType", type, "schemaVersion", 1,
                    "tenantId", offer.tenantId(), "aggregateId", aggregate, "aggregateVersion", offer.version(),
                    "occurredAt", clock.instant(), "correlationId", event, "payload", offer));
            sql.update("INSERT INTO outbox(event_id,event_type,tenant_id,aggregate_id,aggregate_version,payload) VALUES (?,?,?,?,?,?::jsonb)",
                    event, type, offer.tenantId(), aggregate, offer.version(), envelope);
            return offer;
        });
    }

    @Override public Offer get(String tenantId, String merchantId, String offerId) {
        Offer offer = find(tenantId, merchantId, offerId);
        if (offer == null) throw new DomainException(404, "Offer not found.");
        return offer;
    }

    private Offer find(String tenantId, String merchantId, String offerId) {
        Input.identifier(tenantId);
        Input.identifier(merchantId);
        Input.identifier(offerId);
        var records = sql.query("""
                SELECT v.payload::text FROM offer_head h JOIN offer_version v
                USING (tenant_id,merchant_id,offer_id,version)
                WHERE h.tenant_id=? AND h.merchant_id=? AND h.offer_id=?
                """, (rs, row) -> json.readValue(rs.getString(1), Offer.class), tenantId, merchantId, offerId);
        return records.isEmpty() ? null : records.getFirst();
    }

    @Override public Catalog.Verification verify(Catalog.Claim claim) {
        Offer offer = find(claim.tenantId(), claim.merchantId(), claim.offerId());
        return VerificationPolicy.verify(offer, claim, clock.instant(), freshness);
    }

    private static String sha256(String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
