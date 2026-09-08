package dev.sonti.offers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.profiles.active=local-demo")
class ApiTest {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    private record Response(int status, JsonNode body, String contentType) {}
    @Test void fractionalAndNullIntegersAreRejected() throws Exception {
        String base = """
                {"tenantId":"t","merchantId":"m","offerId":"o","currency":"USD","priceMinor":VALUE}
                """;
        for (String value : new String[] {"1.5", "null", "\"2\""}) {
            assertThat(send("POST", "/api/v1/verifications", base.replace("VALUE", value)).status()).isEqualTo(400);
        }
    }
    private Response send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), json.readTree(response.body()),
                    response.headers().firstValue("Content-Type").orElse(""));
        }
    }

    private String offer(String tenant, int version, int price) {
        return """
                {"tenantId":"%s","merchantId":"merchant","offerId":"item","version":%d,
                 "title":"Headphones","priceMinor":%d,"currency":"USD","availableQuantity":1,
                 "sourceUpdatedAt":"%s","deleted":false}
                """.formatted(tenant, version, price, Instant.now().minusSeconds(1));
    }
    @Test void ingestionAndVerificationRoundTrip() throws Exception {
        String tenant = UUID.randomUUID().toString();
        assertThat(send("PUT", "/api/v1/offers", offer(tenant, 1, 1000)).status()).isEqualTo(200);
        var verified = send("POST", "/api/v1/verifications", """
                {"tenantId":"%s","merchantId":"merchant","offerId":"item","priceMinor":1000,"currency":"USD"}
                """.formatted(tenant));
        assertThat(verified.status()).isEqualTo(200);
        assertThat(verified.body().get("outcome").asString()).isEqualTo("VERIFIED");
        assertThat(verified.body().get("sourceVersion").asInt()).isEqualTo(1);
    }
    @Test void sameVersionConflictReturnsProblemDetail() throws Exception {
        String tenant = UUID.randomUUID().toString();
        send("PUT", "/api/v1/offers", offer(tenant, 1, 1000));
        var conflict = send("PUT", "/api/v1/offers", offer(tenant, 1, 999));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.contentType()).contains("application/problem+json");
        assertThat(conflict.body().get("detail").asString()).contains("different facts");
    }
    @Test void missingRequiredPrimitiveDoesNotDefaultToZero() throws Exception {
        var result = send("POST", "/api/v1/verifications",
                "{\"tenantId\":\"tenant\",\"merchantId\":\"merchant\",\"offerId\":\"item\",\"currency\":\"USD\"}");
        assertThat(result.status()).isEqualTo(400);
    }
    @Test void malformedAndInvalidInputReturns400() throws Exception {
        assertThat(send("PUT", "/api/v1/offers", "{broken").status()).isEqualTo(400);
        assertThat(send("PUT", "/api/v1/offers", offer("bad tenant", 1, 1000)).status()).isEqualTo(400);
        assertThat(send("PUT", "/api/v1/offers", offer("tenant", 1, -1)).status()).isEqualTo(400);
    }
    @Test void unknownLookupReturns404WithoutTrace() throws Exception {
        var result = send("GET", "/api/v1/offers/missing/merchant/item", null);
        assertThat(result.status()).isEqualTo(404);
        assertThat(result.body().has("trace")).isFalse();
    }

}
