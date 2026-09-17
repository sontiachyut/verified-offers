package dev.sonti.offers;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Never log bodies, tokens, raw paths, query strings, tenant IDs or user-supplied request IDs. */
@Component
@Order(-110)
final class RequestEvidence extends OncePerRequestFilter {
    private final MeterRegistry meters;
    RequestEvidence(MeterRegistry meters) { this.meters = meters; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) { return !request.getRequestURI().startsWith("/api/"); }
    static String operation(String path) {
        if (path.equals("/api/v1/search")) return "search";
        if (path.equals("/api/v1/verifications")) return "verification";
        if (path.equals("/api/v1/offers") || path.startsWith("/api/v1/offers/")) return "catalog";
        if (path.startsWith("/api/v1/feeds/")) return "feed";
        if (path.equals("/api/v1/readiness")) return "readiness";
        return "other";
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        String id = UUID.randomUUID().toString();
        String operation = operation(request.getRequestURI());
        response.setHeader("X-Request-ID", id); response.setHeader("Cache-Control", "no-store");
        long started = System.nanoTime(); boolean completed = false;
        try { chain.doFilter(request, response); completed = true; }
        finally {
            int status = completed ? response.getStatus() : 500;
            long elapsed = System.nanoTime() - started;
            meters.timer("offers.http.requests", "operation", operation, "result", status / 100 + "xx")
                    .record(elapsed, TimeUnit.NANOSECONDS);
            LoggerFactory.getLogger(RequestEvidence.class).info("request_id={} operation={} status={} duration_ms={}",
                    id, operation, status, TimeUnit.NANOSECONDS.toMillis(elapsed));
        }
    }
}
