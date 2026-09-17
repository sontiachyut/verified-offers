package dev.sonti.offers;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "offers.security.enabled", havingValue = "true")
class JwtSecurityConfiguration {
    @Bean JwtDecoder jwtDecoder(@Value("${offers.security.issuer}") String issuer,
            @Value("${offers.security.audience}") String audience,
            @Value("${offers.security.jwks-uri}") String jwks, Clock clock) {
        endpoint(issuer); endpoint(jwks);
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(2));
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwks).restOperations(new RestTemplate(factory)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
                new TokenPolicy(audience, clock)));
        return decoder;
    }
    static void endpoint(String value) {
        var uri = URI.create(value);
        boolean local = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()))))
            throw new IllegalArgumentException("Identity endpoints require HTTPS or explicit loopback HTTP.");
    }
    @Bean SecurityFilterChain authenticatedSecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.PUT, "/api/v1/offers").hasAuthority("SCOPE_offers:write")
                        .requestMatchers(HttpMethod.GET, "/api/v1/offers/**", "/api/v1/search", "/api/v1/feeds/**").hasAuthority("SCOPE_offers:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/verifications", "/api/v1/claims/extract").hasAuthority("SCOPE_offers:read")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/search").hasAuthority("SCOPE_offers:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/feeds/*/*/*/retry", "/api/v1/feeds/*/*/*/cancel").hasAuthority("SCOPE_offers:operate")
                        .requestMatchers(HttpMethod.POST, "/api/v1/feeds/*/*").hasAuthority("SCOPE_offers:write")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> problem(res, 401))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403)))
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint((req, res, ex) -> problem(res, 401))
                        .accessDeniedHandler((req, res, ex) -> problem(res, 403))).build();
    }
    static void problem(HttpServletResponse response, int status) throws java.io.IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" +
                (status == 401 ? "Authentication required." : "Access denied.") + "\"}");
    }
}
