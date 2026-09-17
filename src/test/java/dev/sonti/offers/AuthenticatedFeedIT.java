package dev.sonti.offers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthenticatedFeedIT extends PostgresFixture {
    @Test void packagedApiProtectsFeedObjectsAndActionsWithRealSignaturesAndDatabase() throws Exception {
        try (var issuer = new TestIssuer(); var app = new RunningApplication("--offers.security.enabled=true",
                "--offers.security.issuer=" + issuer.issuer(), "--offers.security.jwks-uri=" + issuer.issuer() + "/jwks",
                "--offers.security.audience=offers", "--offers.feeds.enabled=true")) {
            String owner = issuer.token("offers:read offers:write offers:operate", c -> {});
            String body = """
                    {"tenantId":"tenant","merchantId":"merchant","offerId":"secured-feed","version":1,
                    "title":"Synthetic keyboard","priceMinor":100,"currency":"USD","availableQuantity":1,
                    "sourceUpdatedAt":"%s","deleted":false}
                    """.formatted(Instant.now().minusSeconds(1)).replace("\n", "");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            var accepted = app.rawRequest("POST", "/api/v1/feeds/tenant/merchant", bytes, Map.of(
                    "Content-Type", "application/x-ndjson", "Authorization", "Bearer " + owner,
                    "Idempotency-Key", "secure-feed", "X-Feed-Source", "test", "X-Content-SHA256", sha), false, 202);
            String id = accepted.path("job").path("id").asString();
            assertThat(id).isNotBlank();
            for (String suffix : new String[] {"", "/" + id, "/" + id + "/rows", "/" + id + "/actions"}) {
                app.rawRequest("GET", "/api/v1/feeds/tenant/merchant" + suffix, null, Map.of("Authorization", "Bearer " + owner), false, 200);
                app.rawRequest("GET", "/api/v1/feeds/other/merchant" + suffix, null, Map.of("Authorization", "Bearer " + owner), false, 403);
                app.rawRequest("GET", "/api/v1/feeds/tenant/other" + suffix, null, Map.of("Authorization", "Bearer " + owner), false, 403);
            }
            byte[] reason = "{\"reason\":\"operator-test\"}".getBytes(StandardCharsets.UTF_8);
            String reader = issuer.token("offers:read", c -> {});
            app.rawRequest("POST", "/api/v1/feeds/tenant/merchant/" + id + "/cancel", reason,
                    Map.of("Authorization", "Bearer " + reader, "Content-Type", "application/json"), false, 403);
            app.rawRequest("POST", "/api/v1/feeds/other/merchant/" + id + "/cancel", reason,
                    Map.of("Authorization", "Bearer " + owner, "Content-Type", "application/json"), false, 403);
            var cancelled = app.rawRequest("POST", "/api/v1/feeds/tenant/merchant/" + id + "/cancel", reason,
                    Map.of("Authorization", "Bearer " + owner, "Content-Type", "application/json"), false, 200);
            assertThat(cancelled.path("state").asString()).isEqualTo("CANCELLED");
            assertThat(sql.queryForObject("SELECT count(*) FROM feed_action WHERE job_id=?::uuid", Integer.class, id)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT actor_subject FROM feed_action WHERE job_id=?::uuid", String.class, id)).isEqualTo("synthetic-user");
            assertThat(sql.queryForObject("SELECT authenticated FROM feed_action WHERE job_id=?::uuid", Boolean.class, id)).isTrue();
        }
    }
}
