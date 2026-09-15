package dev.sonti.offers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

final class OfferEvent {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();

    static Offer parse(String key, String value) {
        if (key == null || key.length() > 194 || value == null
                || value.getBytes(StandardCharsets.UTF_8).length > 65536) throw new IllegalArgumentException("Invalid event.");
        try {
            var event = JSON.readTree(value);
            if (!event.path("schemaVersion").isIntegralNumber() || !event.path("schemaVersion").canConvertToLong()
                    || event.path("schemaVersion").asLong() != 1 || !event.path("aggregateVersion").isIntegralNumber()
                    || !event.path("aggregateVersion").canConvertToLong()) throw new IllegalArgumentException("Invalid event.");
            UUID.fromString(event.path("eventId").asString());
            UUID.fromString(event.path("correlationId").asString());
            Instant.parse(event.path("occurredAt").asString());
            Offer offer = JSON.treeToValue(event.get("payload"), Offer.class);
            String aggregate = offer.merchantId() + ":" + offer.offerId();
            if (!key.equals(offer.tenantId() + ":" + aggregate)
                    || !offer.tenantId().equals(event.path("tenantId").asString())
                    || !aggregate.equals(event.path("aggregateId").asString())
                    || offer.version() != event.path("aggregateVersion").asLong()
                    || !(offer.deleted() ? "OfferDeleted" : "OfferUpdated").equals(event.path("eventType").asString())) {
                throw new IllegalArgumentException("Invalid event.");
            }
            return offer;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid event."); }
    }
}
