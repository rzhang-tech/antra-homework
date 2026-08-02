package com.example.analytics.listener;

import com.example.analytics.event.OrderPlaced;
import com.example.analytics.service.SalesTally;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * The same topic notification-service reads, under a different consumer group.
 *
 * <p>That one difference is the whole of Step 7b. Kafka delivers each message to every consumer
 * <em>group</em> and to exactly one member within a group, so:
 *
 * <ul>
 *   <li>this service and notification-service each receive every {@code OrderPlaced}, independently;
 *   <li>neither can affect the other's progress — this one can be stopped for an hour, or replay the
 *       topic from the beginning, and no confirmation is sent twice or missed;
 *   <li>and order-service knows about neither. It did not change when this service was written.
 * </ul>
 *
 * <p>The alternative shape — order-service calling an analytics API — makes every one of those false.
 * A slow analytics service would slow ordering down, a broken one would fail orders or need a circuit
 * breaker of its own, and adding a third reader would mean editing and redeploying order-service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderPlacedListener {

    private final SalesTally tally;

    @KafkaListener(
            topics = "${app.kafka.topics.order-placed}",
            groupId = "${app.kafka.groups.analytics}")
    public void onOrderPlaced(@Payload OrderPlaced event,
                              @Header(KafkaHeaders.RECEIVED_KEY) String key,
                              @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                              @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Consumed OrderPlaced for order {} from partition {} offset {} with key {}",
                event.orderId(), partition, offset, key);

        tally.record(event);
    }
}
