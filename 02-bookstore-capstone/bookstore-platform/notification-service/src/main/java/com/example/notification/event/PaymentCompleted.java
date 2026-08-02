package com.example.notification.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * This service's copy of payment-service's published contract.
 *
 * <p>The second event type this service consumes, and the reason Step 7a's message-converter decision
 * pays for itself. With a {@code JsonDeserializer} bound to one default type, a second topic carrying a
 * different shape would need a second consumer factory and a second listener container factory wired
 * by hand. Here the converter reads whatever the listener method declares, so a new event type is a new
 * method and nothing else.
 */
public record PaymentCompleted(
        Long paymentId,
        Long orderId,
        Long userId,
        BigDecimal amount,
        Instant paidAt
) {
}
