package com.example.bookstore.security;

import com.example.bookstore.config.JwtProperties;
import com.example.bookstore.entity.Role;
import com.example.bookstore.entity.User;
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
 * Mints and verifies JSON Web Tokens.
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

    private final SecretKey key;
    private final Duration expiration;
    private final String issuer;

    public JwtUtil(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofMinutes(properties.expirationMinutes());
        this.issuer = properties.issuer();
    }

    public String generate(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                // `sub` — who the token is about. The standard place for the principal's identity.
                .subject(user.getUsername())
                // A custom claim, so authorization needs no database lookup on later requests.
                .claim(CLAIM_ROLE, user.getRole().name())
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key)
                .compact();
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

    public Role roleOf(Claims claims) {
        return Role.valueOf(claims.get(CLAIM_ROLE, String.class));
    }
}
