package com.example.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The {@code app.jwt.*} the gateway needs, which is less than any service needs.
 *
 * <p>No {@code expirationMinutes}: this module never issues a token, so how long one lives is not its
 * business. Binding a value a component cannot legitimately use is a small thing that invites a large
 * one — the next person to need "just a quick token" finds the expiry already configured here.
 *
 * <p>Validated, so a missing or short key is a startup failure rather than a 500 on the first request
 * that carries a token. On the public edge that distinction matters more than anywhere else: a gateway
 * that starts without a usable key would reject every authenticated request on the platform while
 * reporting itself healthy.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(

        @NotBlank(message = "app.jwt.secret must be set (env JWT_SECRET in prod)")
        @Size(min = 32, message = "app.jwt.secret must be at least 32 characters for HS256")
        String secret,

        @NotBlank
        String issuer
) {
}
