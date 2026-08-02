package com.example.analytics.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * This service's own copy of order-service's published contract.
 *
 * <p>A copy, on purpose (D12). Sharing a jar of event classes turns every producer's release into a
 * rebuild of every consumer, which is the coupling the split was meant to remove — and it makes the
 * dangerous change easy: rename a field in one place and every consumer compiles happily against the
 * new name while the topic still carries the old one.
 *
 * <p>Because it is a copy, it is always a copy of <em>some past version</em> of the producer's contract.
 * That is not a flaw to be engineered away; it is the actual situation, and it dictates the rule this
 * consumer follows: <strong>unknown fields are ignored, missing fields are tolerated.</strong> The
 * deserializer is configured with {@code FAIL_ON_UNKNOWN_PROPERTIES=false} for exactly that reason, so
 * order-service can add a field tomorrow without notifying anybody.
 *
 * <p>The fields this service actually needs are far fewer than the ones it receives, and it deliberately
 * keeps only those. A consumer that binds every field it is sent has quietly signed up to care about all
 * of them.
 */
public record OrderPlaced(
        Long orderId,
        Long userId,
        BigDecimal totalPrice,
        List<Item> items,
        Instant placedAt
) {
    public record Item(Long bookId, String title, int quantity, BigDecimal unitPrice) {
    }
}
