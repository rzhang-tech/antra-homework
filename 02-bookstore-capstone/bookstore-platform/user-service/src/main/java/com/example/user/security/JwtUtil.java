package com.example.user.security;

import com.example.user.config.JwtProperties;
import com.example.user.entity.Role;
import com.example.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.context.config.annotation.RefreshScope;
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
@RefreshScope
@Slf4j
public class JwtUtil {

    private static final String CLAIM_ROLE = "role";

    /**
     * The user's database id.
     *
     * <p>Added when order-service appeared. An order records who placed it as a plain {@code user_id},
     * and order-service has no access to the users table — so the id has to travel in the token or be
     * fetched over HTTP on every single order. Putting it in a claim removes a network call from the
     * critical path of every request, at the cost of one more field that goes stale if a user is
     * deleted. For an immutable surrogate key that trade is easy.
     *
     * <p>Note what is NOT added: email, or anything else a downstream service might be tempted to
     * display. A token is readable by anyone holding it (see {@link #parse}), so every extra claim is
     * a deliberate disclosure.
     */
    private static final String CLAIM_USER_ID = "uid";

    /*
     * All three are captured once, in the constructor, and that is what @RefreshScope is here for.
     *
     * Without it, POST /actuator/refresh updates the Environment - the endpoint even reports
     * `app.jwt.expiration-minutes` as changed - and this bean carries on minting sixty-minute tokens,
     * because the value was read into a field when the bean was built. **The environment refreshing is
     * not the same as anything refreshing**, and it is the single most misleading thing about config
     * refresh.
     *
     * @RefreshScope makes this a lazy proxy: on refresh the instance is discarded, and the next call
     * builds a new one from the current Environment. The cost is a proxy on every JWT operation and a
     * rebuild on the first call after each refresh, which for a bean this small is nothing.
     *
     * <p>It took two changes, not one. With the annotation alone the rebuilt bean was still handed the
     * same stale {@link JwtProperties}, because a record is bound through its constructor and the
     * refresh machinery rebinds an existing instance. Measured at each stage, changing the configured
     * expiry from 60 to 5 and logging in again:
     *
     * <pre>
     *   no @RefreshScope, JwtProperties a record    env says 5, tokens 60
     *   @RefreshScope,    JwtProperties a record    env says 5, tokens 60
     *   @RefreshScope,    JwtProperties a class     env says 5, tokens  5
     * </pre>
     *
     * <p>Two beans in a chain, and the refresh only lands when <em>both</em> can be rebuilt.
     *
     * ROTATING THE SIGNING KEY IS STILL NOT SAFE THIS WAY, and it is worth being precise about why,
     * because it is the value this whole step was built for. Refresh reaches one service at a time.
     * The instant user-service starts signing with a new key, every token already in a customer's
     * browser - and every service still holding the old key - disagrees with it. Real rotation needs a
     * verifier that accepts both keys for an overlap longer than the token lifetime, and a signer that
     * switches only after every verifier has the new key. That is a code change (a key *set*, not a
     * key), not a configuration change, and this platform does not have it.
     */
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
                .claim(CLAIM_USER_ID, user.getId())
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

    public Long userIdOf(Claims claims) {
        return claims.get(CLAIM_USER_ID, Long.class);
    }

    public Role roleOf(Claims claims) {
        return Role.valueOf(claims.get(CLAIM_ROLE, String.class));
    }
}
