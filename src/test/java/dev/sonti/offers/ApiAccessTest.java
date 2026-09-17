package dev.sonti.offers;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

class ApiAccessTest {
    private final ApiAccess access = new ApiAccess(true);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    private void login(String scopes) {
        var jwt = Jwt.withTokenValue("fixture").header("alg", "RS256")
                .claim("tenant_id", "t").claim("merchant_id", "m").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt,
                java.util.Arrays.stream(scopes.split(" ")).map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toList()));
    }
    @Test void authenticationIsRequired() { assertThatThrownBy(() -> access.read("t")).isInstanceOf(DomainException.class); }
    @Test void crossTenantNeverAllowedEvenForOperator() {
        login("offers:read offers:write offers:operate");
        assertThatThrownBy(() -> access.read("other")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> access.operate("other", "m")).isInstanceOf(DomainException.class);
    }
    @Test void separatesMerchantMutationAndTenantReading() {
        login("offers:read offers:write");
        access.read("t"); access.write("t", "m"); access.feedRead("t", "m");
        assertThatThrownBy(() -> access.write("t", "other")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> access.feedRead("t", "other")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> access.operate("t", "m")).isInstanceOf(DomainException.class);
    }
    @Test void scopesDoNotImplyOneAnother() {
        login("offers:operate"); access.operate("t", "m");
        assertThatThrownBy(() -> access.read("t")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> access.write("t", "m")).isInstanceOf(DomainException.class);
    }
    @Test void demoIsExplicitlyUnauthenticated() { new ApiAccess(false).read("any"); }
}
