package dev.sonti.offers;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class RequestLimitsTest {
    @Test void rejectsOversizedJsonWithNoDownstreamCall() throws Exception {
        var request = new MockHttpServletRequest("PUT", "/api/v1/offers");
        request.setContent(new byte[16_385]);
        var response = new MockHttpServletResponse();
        new RequestLimits().doFilter(request, response, (r, s) -> { throw new AssertionError("Must reject."); });
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void boundsChunkedBodyAndPreservesAcceptedBytes() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/verifications") {
            @Override public long getContentLengthLong() { return -1; }
        };
        var response = new MockHttpServletResponse();
        request.setContent("test-body".getBytes());
        new RequestLimits().doFilter(request, response, (r, s) -> assertThat(r.getInputStream().readAllBytes()).isEqualTo("test-body".getBytes()));
        request.removeAttribute(RequestLimits.class.getName() + ".FILTERED");
        request.setContent(new byte[16_385]);
        new RequestLimits().doFilter(request, response, (r, s) -> { throw new AssertionError("Must reject."); });
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void feedBudgetAndEncodedRequestsAreExplicit() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/feeds/t/m");
        request.setContent(new byte[1_048_576]);
        var response = new MockHttpServletResponse();
        new RequestLimits().doFilter(request, response, (r, s) -> assertThat(r.getInputStream().readAllBytes()).hasSize(1_048_576));
        request.addHeader("Content-Encoding", "gzip");
        new RequestLimits().doFilter(request, response, (r, s) -> { throw new AssertionError("Must reject."); });
        assertThat(response.getStatus()).isEqualTo(415);
    }
}
