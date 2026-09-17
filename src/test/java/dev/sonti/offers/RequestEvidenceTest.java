package dev.sonti.offers;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class RequestEvidenceTest {
    @Test void correlatesWithoutReflectingUntrustedIdentifiersAndBoundsMetricLabels() throws Exception {
        try (var meters = new SimpleMeterRegistry()) {
            var filter = new RequestEvidence(meters);
            for (int i = 0; i < 30; i++) {
                var request = new MockHttpServletRequest("GET", "/api/v1/offers/private-tenant/merchant/" + i);
                request.addHeader("X-Request-ID", "attacker-controlled");
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(404));
                assertThat(response.getHeader("X-Request-ID")).matches("[a-f0-9-]{36}");
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
            }
            assertThat(meters.getMeters()).hasSize(1);
            assertThat(meters.get("offers.http.requests").tag("operation", "catalog").tag("result", "4xx").timer().count()).isEqualTo(30);
        }
    }
}
