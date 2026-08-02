package com.example.user.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code app.jwt.*} configuration, validated at startup.
 *
 * <p>Validating here rather than at first use means a missing or too-short secret is a startup failure
 * — loud, immediate, and before any traffic — instead of a runtime exception on the first login.
 *
 * <h2>Why this is a mutable class and not a record</h2>
 *
 * <p>It was a record until Step 6c, and immutable configuration is the better default: a value object
 * cannot be changed by accident and needs no defensive copying.
 *
 * <p>It could not stay one. A record is bound through its constructor, so the only way to give it new
 * values is to build a new instance — and {@code ConfigurationPropertiesRebinder}, which is what
 * {@code POST /actuator/refresh} uses, rebinds the <em>existing</em> bean in place. Measured before this
 * change: the refresh endpoint reported {@code app.jwt.expiration-minutes} as changed, and the next
 * login still produced a sixty-minute token. Adding {@code @RefreshScope} to {@link
 * com.example.user.security.JwtUtil} did not help either, because the rebuilt {@code JwtUtil} was handed
 * the same stale record.
 *
 * <p>So the trade is explicit: <strong>immutability, in exchange for the ability to change a value
 * without a restart.</strong> Worth it here because these values are exactly the kind operations needs
 * to adjust under pressure. The setters are for the binder; nothing in this codebase calls them, and
 * anything that did would be changing configuration behind {@code JwtUtil}'s back.
 *
 * <p>The other three services keep the record form. They have no reason to refresh a signing key
 * they only verify with — see {@code JwtUtil}'s note on why key rotation is not a refresh problem.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
@Getter
@Setter
// The binder needs the no-arg constructor; keeping the all-args one lets tests build an instance in a
// line, as they could when this was a record. Both being present is also what tells Spring Boot to use
// JavaBean binding rather than constructor binding - and JavaBean binding is the whole point here,
// because only it can rebind an existing instance on refresh.
@NoArgsConstructor
@AllArgsConstructor
public class JwtProperties {

    /**
     * The HMAC signing key. Anyone holding it can mint a token for any user with any role, so it is
     * a credential in the strictest sense.
     *
     * <p>HS256 requires at least 256 bits of key material; the minimum below enforces that in
     * characters. JJWT would reject a shorter key anyway — this just fails earlier with a clearer
     * message.
     */
    @NotBlank(message = "app.jwt.secret must be set (env JWT_SECRET in prod)")
    @Size(min = 32, message = "app.jwt.secret must be at least 32 characters for HS256")
    private String secret;

    @Positive
    private long expirationMinutes;

    @NotBlank
    private String issuer;

    /** Kept so call sites read the same as they did when this was a record. */
    public String secret() {
        return secret;
    }

    public long expirationMinutes() {
        return expirationMinutes;
    }

    public String issuer() {
        return issuer;
    }
}
