package com.example.user;

import com.example.user.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Owns users and is the only service that issues tokens.
 *
 * <p>Every other service verifies what this one signs, which makes the signing key the platform's
 * single most sensitive piece of configuration — and the reason it moves to a config server in Step 6
 * and to a Kubernetes Secret in Step 10.
 */
@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
