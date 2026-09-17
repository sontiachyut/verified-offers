package dev.sonti.offers;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Compare untrusted route/body scope with authenticated identity before any data access. */
@Component
final class ApiAccess {
    private final boolean enabled;
    ApiAccess(@Value("${offers.security.enabled:false}") boolean enabled) { this.enabled = enabled; }
    void read(String tenant) { check(tenant, null, "offers:read"); }
    void feedRead(String tenant, String merchant) { check(tenant, merchant, "offers:read"); }
    void write(String tenant, String merchant) { check(tenant, merchant, "offers:write"); }
    void operate(String tenant, String merchant) { check(tenant, merchant, "offers:operate"); }
    private void check(String tenant, String merchant, String scope) {
        if (!enabled) return;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated())
            throw new DomainException(401, "Authentication required.");
        if (!tenant.equals(auth.getToken().getClaimAsString("tenant_id"))
                || (merchant != null && !merchant.equals(auth.getToken().getClaimAsString("merchant_id")))
                || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("SCOPE_" + scope)))
            throw new DomainException(403, "Access denied.");
    }
}
