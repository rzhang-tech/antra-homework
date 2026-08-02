package com.example.notification.listener;

import com.example.notification.event.PaymentCompleted;
import com.example.notification.service.ReceiptSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * A second topic, a second event type, the same consumer group.
 *
 * <p>Same group as {@link OrderPlacedListener} because this is one service doing one service's work:
 * scaling it to two instances should split <em>all</em> of its subscriptions between them, not give
 * each instance a different half of the platform's events. Group per service, not group per listener.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCompletedListener {

    private final ReceiptSender sender;

    @KafkaListener(
            topics = "${app.kafka.topics.payment-completed}",
            groupId = "${app.kafka.groups.notification}")
    public void onPaymentCompleted(@Payload PaymentCompleted event,
                                   @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                   @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Consumed PaymentCompleted for order {} from partition {} offset {}",
                event.orderId(), partition, offset);

        sender.send(event);
    }
}
