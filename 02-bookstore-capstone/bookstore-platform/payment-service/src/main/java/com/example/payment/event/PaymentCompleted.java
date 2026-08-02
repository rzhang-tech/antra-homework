package com.example.payment.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What payment-service announces when money has actually moved.
 *
 * <p>Only on success. A declined payment is not published, and that is a decision rather than an
 * omission: an event stream is read by consumers who cannot be enumerated, and "payment failed" is
 * information a fraud service would want and a receipt service must never act on. Publishing an
 * outcome invites every consumer to interpret it, so this topic carries facts that are safe for all of
 * them. A separate {@code payment.declined} topic is the shape that scales, if anyone ever needs one.
 *
 * <p>Keyed by order id like {@code OrderPlaced}, deliberately. The same key puts a payment on the same
 * partition as... nothing, in fact — different topic, so nothing is co-ordered with it. What the key
 * does buy is that two events about one order (a retry, a correction) stay in order relative to each
 * other, and that a consumer joining both topics can reason about one order without a global sort.
 *
 * @param paymentId the payment's own identity
 * @param orderId   the natural idempotency key downstream, exactly as in 5e - one payment per order
 * @param amount    what was charged, captured rather than looked up
 * @param paidAt    when the money moved, not when this message was sent
 */
public record PaymentCompleted(
        Long paymentId,
        Long orderId,
        Long userId,
        BigDecimal amount,
        Instant paidAt
) {
}
