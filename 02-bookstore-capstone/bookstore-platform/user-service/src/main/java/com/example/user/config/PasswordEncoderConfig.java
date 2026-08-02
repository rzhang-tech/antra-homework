package com.example.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordEncoderConfig {

    /**
     * BCrypt, which is a password hash rather than a general-purpose one.
     *
     * <p>SHA-256 and friends are built to be <em>fast</em>, which is exactly wrong here: a modern GPU
     * runs billions of SHA-256 hashes a second, so a stolen table of them is a dictionary attack away
     * from being a table of passwords. BCrypt is deliberately slow and its cost is tunable, so hardware
     * getting faster is answered by raising the work factor rather than by migrating algorithms.
     *
     * <p>It also salts every password automatically. The salt is random per user and stored inside the
     * output string, so two users with the same password get different hashes — which defeats rainbow
     * tables and stops the database from revealing who shares a password with whom.
     *
     * <p>A BCrypt hash carries its own parameters:
     * <pre>
     *   $2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
     *    │   │  └──────────── 22-char salt ────────┘└──── 31-char hash ────┘
     *    │   └── cost factor: 2^10 rounds
     *    └────── algorithm version
     * </pre>
     * Because the cost is recorded in the string, raising it later still verifies old hashes correctly.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 10 is Spring's default: roughly 100 ms per hash on current hardware, which is
        // negligible for a login and ruinous for an attacker doing it billions of times.
        return new BCryptPasswordEncoder(10);
    }
}
