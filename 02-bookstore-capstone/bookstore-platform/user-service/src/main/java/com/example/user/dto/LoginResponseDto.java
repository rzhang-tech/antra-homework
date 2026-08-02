package com.example.user.dto;

import com.example.user.entity.Role;

/**
 * What a successful login returns.
 *
 * <p>{@code tokenType} is included so the client knows to send {@code Authorization: Bearer <token>},
 * and {@code expiresInSeconds} so it can refresh before expiry rather than discovering it through a
 * failed request. Both are conventional in OAuth 2.0 token responses.
 *
 * <p>{@code username} and {@code role} are here for convenience — a browser client can render the right
 * UI without decoding the token. They are not authoritative: the server re-reads them from the signed
 * token on every request, and never trusts what a client sends back.
 */
public record LoginResponseDto(
        String token,
        String tokenType,
        long expiresInSeconds,
        String username,
        Role role
) {

    public static LoginResponseDto of(String token, long expiresInSeconds, String username, Role role) {
        return new LoginResponseDto(token, "Bearer", expiresInSeconds, username, role);
    }
}
