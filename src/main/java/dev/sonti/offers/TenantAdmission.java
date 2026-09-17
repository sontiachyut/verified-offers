package dev.sonti.offers;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-95)
@ConditionalOnProperty(name = "offers.security.enabled", havingValue = "true")
final class TenantAdmission extends OncePerRequestFilter {
    private final TenantBudget budget = new TenantBudget(1024, 60, 30, System::nanoTime);
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (request.getRequestURI().startsWith("/api/") && auth instanceof JwtAuthenticationToken jwt
                && !budget.acquire(jwt.getToken().getClaimAsString("tenant_id"))) {
            response.setHeader("Retry-After", "1"); RequestLimits.reject(response, 429); return;
        }
        chain.doFilter(request, response);
    }
}
