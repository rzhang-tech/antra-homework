package com.example.notification.service;

import com.example.notification.event.OrderPlaced;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * What this service actually does, separated from how the message reached it.
 *
 * <p>The split is not ceremony. A {@code @KafkaListener} method is bound to a topic, a group and a
 * header layout; everything it contains can only be exercised by producing a real message. Keeping the
 * work in an ordinary bean means the plumbing is tested once — does JSON from another service bind to
 * this service's own copy of the record — and the behaviour is tested like any other method.
 *
 * <p>It is also where Step 7c's idempotency will go, and that belongs on this side of the line:
 * "have I already confirmed this order?" is a question about the work, not about Kafka.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConfirmationSender {

    private final ProcessedOrders processed;

    /**
     * Sends the customer their order confirmation.
     *
     * <p>A log line rather than an email. The integration would add setup and no lesson; what matters
     * for the capstone is that this runs on somebody else's thread, possibly minutes later, and that
     * nothing upstream waited for it.
     */
    public void send(OrderPlaced event) {
        // Step 7c. The guard lives here, not in the listener: "have I already confirmed this order?"
        // is a question about the work, and it would need answering just the same if these events
        // arrived over HTTP or were replayed from a file during a migration.
        if (!processed.firstTimeSeeing(event.orderId())) {
            return;
        }

        log.info("CONFIRMATION to user {}: order {} accepted, {} item(s), total {} (placed {})",
                event.userId(), event.orderId(), event.items().size(),
                event.totalPrice(), event.placedAt());
    }
}
