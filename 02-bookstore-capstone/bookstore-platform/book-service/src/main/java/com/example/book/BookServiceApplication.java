package com.example.book;

import com.example.book.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Owns the catalog and stock.
 *
 * <p>It verifies tokens but cannot issue them: there is no login endpoint and no
 * {@code AuthenticationManager} here. It shares the signing key with user-service and nothing else —
 * in particular, not a database.
 */
@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class BookServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BookServiceApplication.class, args);
    }
}
