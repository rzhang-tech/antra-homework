package com.example.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Mints tokens the gateway will accept.
 *
 * <p>Exists because Step 8b made every routing test that touches a protected path need one. That was
 * itself the useful signal: {@code RoutingTest} had been sending {@code Bearer a.b.c} and getting 200,
 * and the moment the edge filter appeared those tests failed — which is the filter proving it is in the
 * path rather than merely configured.
 *
 * <p>Signed with the key from {@code application-test.yml}, on purpose. A helper that signed with a
 * different key could only demonstrate that two keys disagree; these tests are about genuine tokens
 * being accepted and forged ones refused.
 */
final class TestTokens {

    /** Matches application-test.yml. Nothing signed here outlives the test JVM. */
    static final String SECRET = "test-only-signing-key-not-used-anywhere-else-0123456789";

    private TestTokens() {
    }

    static String customer() {
        return forUser("shopper", 7L, "USER", Duration.ofMinutes(60));
    }

    static String forUser(String username, long userId, String role, Duration validFor) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .claim("uid", userId)
                .issuer("bookstore")
                .issuedAt(Date.from(Instant.now().minus(Duration.ofMinutes(5))))
                .expiration(Date.from(Instant.now().plus(validFor)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
