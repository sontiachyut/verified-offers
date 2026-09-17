package dev.sonti.offers;

import java.time.Clock;
import java.time.Duration;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Additional access-token constraints; signature/issuer validation belongs to the decoder. */
final class TokenPolicy implements OAuth2TokenValidator<Jwt> {
    private final String audience;
    private final Clock clock;
    TokenPolicy(String audience, Clock clock) {
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("Audience required.");
        this.audience = audience; this.clock = clock;
    }
    @Override public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            var now = clock.instant();
            var issued = token.getIssuedAt();
            var expires = token.getExpiresAt();
            Input.identifier(token.getClaimAsString("tenant_id"));
            Input.identifier(token.getClaimAsString("merchant_id"));
            if (token.getSubject() == null || token.getSubject().isBlank() || token.getSubject().length() > 200
                    || !token.getAudience().contains(audience) || issued == null || expires == null
                    || issued.isAfter(now) || !expires.isAfter(now) || !expires.isAfter(issued)
                    || Duration.between(issued, expires).compareTo(Duration.ofHours(1)) > 0
                    || (token.getNotBefore() != null && token.getNotBefore().isAfter(now))) return invalid();
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException invalidClaim) { return invalid(); }
    }
    private static OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token.", null));
    }
}
