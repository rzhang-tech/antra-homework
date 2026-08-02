package com.example.book.security;

import com.example.book.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Verifies JSON Web Tokens. It cannot mint them.
 *
 * <p>The {@code generate} method was deleted when this service was split out of the monolith. Only
 * user-service issues tokens; book-service holds the same signing key solely to check signatures.
 * Leaving the method here would let a future change quietly turn the catalog service into a second
 * identity provider — and two services minting credentials is two places to audit.
 *
 * <p>A JWT is three base64url segments joined by dots:
 *
 * <pre>
 *   eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJydW95dSIsInJvbGUiOiJVU0VSIn0 . 4pQ2f1_KcZ...
 *   └──── header ──────┘   └──────────── payload (claims) ───────┘   └ signature ┘
 * </pre>
 *
 * <p><strong>The first two segments are encoded, not encrypted.</strong> Anyone can decode and read
 * them — paste a token into jwt.io and the username and role are right there. A JWT protects
 * <em>integrity</em>, not confidentiality: the signature proves the claims have not been altered since
 * this server signed them. Never put anything secret in a token.
 *
 * <p>That signature is why the server needs no session store. It does not remember issuing the token;
 * it recomputes the signature from the payload and its own key, and a match proves authenticity. Change
 * one character of the payload and the signature no longer matches.
 *
 * <p>The flip side: a token cannot be revoked. Until it expires it stays valid, even if the user is
 * deleted or demoted. That is the price of statelessness, and the reason expiry is measured in minutes
 * rather than weeks.
 */
@Component
@Slf4j
public class JwtUtil {

    private static final String CLAIM_ROLE = "role";

    /**
     * The user's database id, added to the token in Step 5b so that services with no users table can
     * still record <em>who</em> did something. book-service ignored it until Step 9, when browsing
     * history gave it its first reason to care which person is asking rather than which role.
     */
    private static final String CLAIM_USER_ID = "uid";

    private final SecretKey key;
    private final Duration expiration;
    private final String issuer;

    public JwtUtil(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofMinutes(properties.expirationMinutes());
        this.issuer = properties.issuer();
    }

    /**
     * Verifies the signature, issuer, and expiry, returning the claims if all hold.
     *
     * <p>Returns empty rather than throwing: an invalid token is an ordinary event on a public
     * endpoint — expired, truncated, or simply forged — not an exceptional one. The filter treats the
     * request as anonymous and lets the authorization rules decide the status code.
     */
    public Optional<Claims> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            // Logged at debug, without the token: a valid one is a bearer credential, and writing it
            // to the log makes the log as sensitive as a password file.
            log.debug("Rejected JWT: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    public String usernameOf(Claims claims) {
        return claims.getSubject();
    }

    /**
     * The role claim, as the raw string user-service put there.
     *
     * <p>Deliberately not parsed into an enum. book-service does not own the set of roles — user-service
     * does — and copying that enum here would mean the catalog crashes in its security filter the day
     * someone adds a role upstream. Returning the string lets an unrecognised role simply match no
     * authorization rule, which is a 403: the safe outcome, and a decision this service is entitled to
     * make. Sharing the enum in a common jar would trade this small duplication for a lockstep release
     * between two services that otherwise have nothing to do with each other (D12).
     */
    /**
     * May be null for a token minted before {@code uid} existed, or by a service token that represents
     * no person. Callers must cope: recording browsing history for nobody is skipped, not crashed.
     */
    public Long userIdOf(Claims claims) {
        return claims.get(CLAIM_USER_ID, Long.class);
    }

    public String roleOf(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }
}
