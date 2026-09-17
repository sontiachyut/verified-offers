package dev.sonti.offers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;

class TokenPolicyTest {
    private final Instant now = Instant.parse("2026-09-16T12:00:00Z");
    private final TokenPolicy policy = new TokenPolicy("offers", Clock.fixed(now, ZoneOffset.UTC));
    private Jwt.Builder token() {
        return Jwt.withTokenValue("fixture").header("alg", "RS256").subject("reader")
                .audience(List.of("offers")).issuedAt(now.minusSeconds(10)).expiresAt(now.plusSeconds(60))
                .claim("tenant_id", "tenant").claim("merchant_id", "merchant");
    }
    @Test void acceptsBoundedScopedCredential() { assertThat(policy.validate(token().build()).hasErrors()).isFalse(); }
    @Test void rejectsInvalidScopeAndAudience() {
        for (Jwt jwt : List.of(token().claim("tenant_id", "bad tenant").build(),
                token().claim("merchant_id", "../m").build(), token().audience(List.of("another")).build(),
                token().subject("").build())) assertThat(policy.validate(jwt).hasErrors()).isTrue();
    }
    @Test void rejectsMissingAndInvalidTimes() {
        for (Jwt jwt : List.of(token().issuedAt(now.plusSeconds(1)).build(), token().expiresAt(now).build(),
                token().notBefore(now.plusSeconds(1)).build(), token().expiresAt(now.plusSeconds(3601)).build(),
                token().claims(c -> c.remove("exp")).build(), token().claims(c -> c.remove("iat")).build()))
            assertThat(policy.validate(jwt).hasErrors()).isTrue();
    }
}
