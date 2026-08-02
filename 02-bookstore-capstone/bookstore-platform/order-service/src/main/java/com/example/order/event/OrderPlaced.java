package com.example.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What order-service announces when an order has been accepted.
 *
 * <p><strong>This is a published contract, not an internal DTO.</strong> The difference matters more
 * than it looks. A REST response is read by a caller that is currently talking to you, so a bad change
 * fails immediately and visibly. An event is read by consumers you cannot see, possibly hours later,
 * possibly by a service written after this one — so the only safe changes are additive ones. Removing a
 * field or changing its meaning breaks a consumer that nobody remembers deploying.
 *
 * <p>Each service declares its own copy of this record rather than sharing a jar (D12). Duplication is
 * the price of not making every consumer redeploy when the producer's build changes, and it also forces
 * the honest question: consumers must tolerate fields they do not know about, because a copy is always a
 * copy of some past version.
 *
 * <p>What is deliberately <em>not</em> here: anything a consumer could look up, and anything private.
 * No email address, no payment details. An event lands in a log, a topic retained for days, and every
 * consumer group on the platform.
 *
 * @param orderId    the natural identity of this event - one order is placed once. Used as the Kafka
 *                   message key, which is what puts every event for one order on one partition and
 *                   therefore in order, and used again by consumers to recognise a redelivery.
 * @param userId     who placed it, so a consumer can address them without calling user-service
 * @param totalPrice captured, not referenced - the same reasoning as order_item's unit_price in 5b
 * @param items      enough to write a confirmation without calling anybody
 * @param placedAt   when the order was accepted, NOT when this message was sent. A consumer that is
 *                   two hours behind still needs to say the right thing.
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
