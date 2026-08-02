package com.example.user.dto;

import com.example.user.entity.Role;
import com.example.user.entity.User;

import java.time.Instant;

/**
 * A user, as the API is willing to describe one.
 *
 * <p>{@code passwordHash} is absent, and that absence is the whole point. Returning the entity directly
 * would put every user's hash into the response of every endpoint that mentions a user — the exact leak
 * the DTO layer exists to prevent (see D3). A hash is not a password, but it is offline-crackable
 * material and has no business leaving the server.
 */
public record UserResponseDto(
        Long id,
        String username,
        String email,
        Role role,
        Instant createdAt
) {

    public static UserResponseDto from(User user) {
        return new UserResponseDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
