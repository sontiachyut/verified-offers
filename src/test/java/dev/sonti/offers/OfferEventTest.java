package dev.sonti.offers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OfferEventTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Offer offer = new Offer("demo", "merchant", "item", 1, "Keyboard", 999, "USD", 4, Instant.now(), false);
    private String envelope() {
        UUID id = UUID.randomUUID();
        return json.writeValueAsString(Map.of("eventId", id, "eventType", "OfferUpdated", "schemaVersion", 1,
                "tenantId", "demo", "aggregateId", "merchant:item", "aggregateVersion", 1,
                "occurredAt", Instant.now(), "correlationId", id, "payload", offer));
    }
    @Test void acceptsValidSnapshotButRejectsWrongKeyTypeVersionAndTenant() {
        String valid = envelope();
        assertThat(OfferEvent.parse("demo:merchant:item", valid)).isEqualTo(offer);
        assertThatIllegalArgumentException().isThrownBy(() -> OfferEvent.parse("other:merchant:item", valid));
        for (String invalid : new String[]{valid.replace("OfferUpdated", "OfferDeleted"),
                valid.replace("\"aggregateVersion\":1", "\"aggregateVersion\":2"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":99"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":18446744073709551617"),
                valid.replace("\"priceMinor\":999", "\"priceMinor\":999.9"),
                valid.replace("\"version\":1", "\"version\":\"1\""),
                valid.replace("\"deleted\":false", "\"deleted\":null"),
                valid.replace("\"availableQuantity\":4,", ""), "{}", "null", "x".repeat(65537)}) {
            assertThatIllegalArgumentException().as(invalid.substring(0, Math.min(invalid.length(), 100)))
                    .isThrownBy(() -> OfferEvent.parse("demo:merchant:item", invalid));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> OfferEvent.parse("demo:merchant:item", null));
    }
}
