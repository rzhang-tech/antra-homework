package com.example.payment.client;

import com.example.payment.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Mints a token for payment-service to act as itself.
 *
 * <p><strong>Why this exists.</strong> Forwarding the caller's token works for anything happening inside
 * a request. {@link PaymentRecoveryJob} does not: it runs on a timer, minutes or hours after the
 * customer went away, with no request context and therefore no token to forward. The first version of
 * that job called order-service anonymously and got 401 back on every attempt — a recovery mechanism
 * that could never recover anything, failing quietly in a log nobody was reading.
 *
 * <p>This is the general shape of the problem: <em>identity propagation covers synchronous work only.</em>
 * Anything asynchronous — a scheduled job, a queue consumer, a retry after the caller has gone — needs an
 * identity of its own.
 *
 * <h2>The uncomfortable part</h2>
 *
 * <p>Step 5a deleted {@code JwtUtil.generate} from book-service, on the argument that two services able
 * to mint credentials is two places to audit. This adds that capability back, here. The argument has not
 * changed and the situation has: book-service only ever acts on behalf of a caller who is present, while
 * payment-service must act autonomously to finish a saga. A service that acts on its own needs an
 * identity of its own; there is no way round it.
 *
 * <p>What limits the damage:
 *
 * <ul>
 *   <li><strong>Minted per call, and short-lived.</strong> Two minutes, never stored. There is no
 *       long-lived service credential sitting anywhere to be stolen.</li>
 *   <li><strong>Used on exactly one code path.</strong> {@code FeignAuthPropagation} reaches for it only
 *       when there is no request in flight — every customer-initiated call still travels on the
 *       customer's own token, and is authorised as them.</li>
 *   <li><strong>Identifiable in a log.</strong> The subject is {@code service:payment-service}, so an
 *       audit trail can tell a service-initiated action from a customer's.</li>
 * </ul>
 *
 * <p>What does <em>not</em> limit the damage, and should be recorded as such: the role is ADMIN, because
 * order-service's ownership check admits the owner or an admin and this service is neither. That is more
 * authority than the job needs — it only ever calls one endpoint. A real deployment would use a distinct
 * {@code SERVICE} role granted exactly that route, or mutual TLS with an internal-only endpoint and no
 * bearer token at all. Both are Step 8 territory, once a gateway exists to separate inside from outside.
 */
@Component
public class ServiceTokenProvider {

    private static final Duration LIFETIME = Duration.ofMinutes(2);

    private final SecretKey key;
    private final String issuer;

    public ServiceTokenProvider(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.issuer();
    }

    public String mint() {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject("service:payment-service")
                .claim("role", "ADMIN")
                // A uid is required by order-service's filter; -1 is not a real user, which is the
                // point — an ownership check comparing against it will never accidentally match.
                .claim("uid", -1L)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(LIFETIME)))
                .signWith(key)
                .compact();
    }
}
