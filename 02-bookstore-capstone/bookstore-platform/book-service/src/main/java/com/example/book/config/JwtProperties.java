package com.example.book.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code app.jwt.*} configuration, validated at startup.
 *
 * <p>Validating here rather than at first use means a missing or too-short secret is a startup failure
 * — loud, immediate, and before any traffic — instead of a runtime exception on the first login.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(

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
        String secret,

        @Positive
        long expirationMinutes,

        @NotBlank
        String issuer
) {
}
