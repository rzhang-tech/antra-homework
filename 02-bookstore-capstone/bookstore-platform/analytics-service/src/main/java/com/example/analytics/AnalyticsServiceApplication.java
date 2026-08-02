package com.example.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Counts what the shop sells. Reacts; never asked.
 *
 * <p>The second reader of {@code bookstore.order.placed}, and the reason this platform now has an
 * argument rather than an anecdote. notification-service was one consumer; two consumers is what
 * demonstrates the property: order-service did not change, did not learn a new address, and did not
 * even restart when this service appeared. Adding a reader to an event stream is a non-event for the
 * writer, which is exactly what a synchronous call can never offer.
 *
 * <p>This one also raises the stakes on duplicates. A redelivered message costs notification-service a
 * second email — irritating, obvious, survivable. It costs this service the truth: revenue counted
 * twice looks exactly like revenue. Step 7c is about that.
 */
@SpringBootApplication
@EnableScheduling   // for DeadLetterMonitor
public class AnalyticsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
