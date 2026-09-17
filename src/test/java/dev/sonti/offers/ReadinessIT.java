package dev.sonti.offers;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReadinessIT extends PostgresFixture {
    @Test void livenessIsNotSearchReadiness() throws Exception {
        try (var unused = new java.net.ServerSocket(0)) {
            int port = unused.getLocalPort(); unused.close();
            try (var app = new RunningApplication("--offers.search.enabled=true", "--offers.search.endpoint=http://127.0.0.1:" + port,
                    "--offers.search.index=unavailable")) {
                assertThat(app.request("GET", "/actuator/health", null, 200).path("status").asString()).isEqualTo("UP");
                var readiness = app.request("GET", "/api/v1/readiness", null, 503);
                assertThat(readiness.path("dependencies").path("postgres").asString()).isEqualTo("UP");
                assertThat(readiness.path("dependencies").path("search").asString()).isEqualTo("DOWN");
            }
        }
    }
}
