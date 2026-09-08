package dev.sonti.offers;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PostgresProcessIT extends PostgresFixture {
    @BeforeEach void reset() {
        sql.execute("TRUNCATE offer_key,offer_head,offer_version,outbox CASCADE");
    }
    @Test void committedStateSurvivesProcessKillAndRestart() throws Exception {
        var offer = new Offer("tenant", "merchant", "item", 1, "Headphones", 1000, "USD", 1, Instant.now().minusSeconds(1), false);
        try (var first = new RunningApplication()) {
            first.request("PUT", "/api/v1/offers", offer, 200);
            first.crash();
        }
        try (var restarted = new RunningApplication()) {
            assertThat(restarted.request("GET", "/api/v1/offers/tenant/merchant/item", null, 200).get("version").asLong()).isEqualTo(1);
            restarted.request("PUT", "/api/v1/offers", offer, 200);
            var verified = restarted.request("POST", "/api/v1/verifications", new Catalog.Claim("tenant", "merchant", "item", 1000, "USD"), 200);
            assertThat(verified.get("outcome").asString()).isEqualTo("VERIFIED");
            assertThat(sql.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
        }
    }

}
