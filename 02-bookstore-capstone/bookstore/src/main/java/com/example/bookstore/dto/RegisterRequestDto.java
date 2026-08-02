package com.example.bookstore.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A registration request.
 *
 * <p>Note what is absent: {@code role}. If a client could send it, anyone could register as an ADMIN —
 * a privilege-escalation hole created purely by trusting a request field. The server assigns
 * {@code USER} and nothing else can change it through this endpoint.
 */
public record RegisterRequestDto(

        @NotBlank(message = "username is required")
        @Size(min = 3, max = 50, message = "username must be between 3 and 50 characters")
        String username,

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        /**
         * The plaintext password, which exists only in memory for the length of this request: it is
         * hashed immediately and never stored, logged, or returned.
         *
         * <p>The maximum is not arbitrary. BCrypt only considers the first 72 bytes of its input, so a
         * longer password is silently truncated — two passwords sharing a 72-byte prefix would be the
         * same password. Rejecting them is honest; accepting them quietly is not.
         */
        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        String password
) {

    /**
     * Masks the password.
     *
     * <p>Not decoration. A record's generated {@code toString} includes every component, and
     * {@code LoggingAspect} logs each service method's arguments — so registering a user would have
     * written the plaintext password into the application log at DEBUG level, from where it reaches log
     * aggregation, backups, and support tickets. Overriding {@code toString} fixes it at the source
     * rather than at each of the places that might print it.
     */
    @Override
    public String toString() {
        return "RegisterRequestDto[username=" + username + ", email=" + email + ", password=****]";
    }
}
