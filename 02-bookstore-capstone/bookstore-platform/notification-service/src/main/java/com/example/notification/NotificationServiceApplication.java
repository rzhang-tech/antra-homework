package com.example.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Tells customers things happened. Reacts; never asked.
 *
 * <p>The first service on this platform with no API. Nothing routes to it, nothing waits for it, and no
 * customer request fails when it is down — the events it missed are still on the topic when it returns.
 * That is what "decoupled" buys, stated concretely rather than as an adjective.
 */
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
