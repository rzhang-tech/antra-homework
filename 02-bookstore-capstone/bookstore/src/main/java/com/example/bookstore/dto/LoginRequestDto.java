package com.example.bookstore.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequestDto(

        @NotBlank(message = "username is required")
        String username,

        @NotBlank(message = "password is required")
        String password
) {

    /** Masked for the same reason as {@link RegisterRequestDto#toString()} — the aspect logs arguments. */
    @Override
    public String toString() {
        return "LoginRequestDto[username=" + username + ", password=****]";
    }
}
