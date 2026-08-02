package com.example.notification.listener;

import com.example.notification.event.OrderPlaced;
import com.example.notification.service.ConfirmationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Sends the order confirmation. Or would, if this were a real inbox.
 *
 * <p>Logging instead of sending is the honest scope for a capstone — an SMTP integration would add
 * nothing to the lesson and a great deal to the setup. What is real is everything around it: the
 * subscription, the consumer group, the offset, and the fact that this work happens on somebody else's
 * thread, minutes late if necessary, without the customer ever knowing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderPlacedListener {

    private final ConfirmationSender sender;

    /**
     * Consumes {@code OrderPlaced} in this service's own consumer group.
     *
     * <p><strong>The group id is the important parameter.</strong> Kafka delivers each message to every
     * consumer <em>group</em>, and to exactly one member within a group. So two instances of this
     * service share the work and each order is confirmed once; analytics-service, in a different group
     * (Step 7b), receives its own copy of the same message. One topic, two independent readers, neither
     * aware of the other and neither able to affect the other's progress — which is what makes adding a
     * consumer a non-event for the producer.
     *
     * <p>Partition, offset and key are pulled out of the headers and logged deliberately. Those are the
     * numbers that turn "the confirmation never arrived" into an answerable question: exactly which
     * message, on which partition, and whether this group had reached it yet.
     *
     * <p><strong>Throwing from here is not free.</strong> The container retries, and because ordering
     * is per partition, a message that always fails blocks every later message on the same partition
     * behind it. That is the poison-message problem, and Step 7d's dead letter topic answers it.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.order-placed}",
            groupId = "${app.kafka.groups.notification}")
    public void onOrderPlaced(@Payload OrderPlaced event,
                              @Header(KafkaHeaders.RECEIVED_KEY) String key,
                              @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                              @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Consumed OrderPlaced for order {} from partition {} offset {} with key {}",
                event.orderId(), partition, offset, key);

        sender.send(event);
    }
}
