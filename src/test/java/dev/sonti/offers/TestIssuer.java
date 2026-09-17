package dev.sonti.offers;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.function.Consumer;

/** Ephemeral real RSA issuer; no fixture private key leaves this process. */
final class TestIssuer implements AutoCloseable {
    private final RSAKey key;
    private final HttpServer server;
    TestIssuer() {
        try {
            key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                var body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    String issuer() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    String token(String scope, Consumer<JWTClaimsSet.Builder> change) throws Exception {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer()).subject("synthetic-user")
                .audience("offers").issueTime(Date.from(now.minusSeconds(1))).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("tenant_id", "tenant").claim("merchant_id", "merchant").claim("scope", scope);
        change.accept(claims);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
    @Override public void close() { server.stop(0); }
}
