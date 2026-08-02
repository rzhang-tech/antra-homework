package com.example.payment.event;

import com.example.payment.entity.Payment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Announces that a payment succeeded.
 *
 * <p>Note what this does <em>not</em> replace. payment-service still calls order-service synchronously
 * to mark the order paid, and 5e built a recovery job that never gives up until it does. Publishing an
 * event instead would be the tidier architecture and the wrong one here: order-service needs the money
 * to have arrived before it hands over books, and "eventually, once a consumer catches up" is not a
 * guarantee anyone wants standing between a customer and their order. <strong>An event tells people
 * something happened; a call makes something happen.</strong> This is the first, and the call stays.
 *
 * <p>What the event adds is everyone else — receipts today, fraud scoring and revenue reporting later —
 * none of whom payment-service should have to know about.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafka;

    @Value("${app.kafka.topics.payment-completed}")
    private String paymentCompletedTopic;

    /**
     * Publishes {@link PaymentCompleted}, keyed by order id.
     *
     * <p>Same dual-write hole as {@code OrderEventPublisher}, and the same reasoning about which way to
     * fail: the charge is already committed, so a broker that cannot be reached must not turn a
     * successful payment into an error the customer sees. Logged, swallowed, and honest about it.
     */
    public void paymentCompleted(Payment payment) {
        PaymentCompleted event = new PaymentCompleted(
                payment.getId(),
                payment.getOrderId(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getPaidAt());

        try {
            kafka.send(paymentCompletedTopic, String.valueOf(payment.getOrderId()), event);
            log.info("Published PaymentCompleted for order {} to {}",
                    payment.getOrderId(), paymentCompletedTopic);
        } catch (RuntimeException ex) {
            log.error("Payment {} for order {} succeeded but PaymentCompleted could not be published "
                            + "({}). No receipt will be sent.",
                    payment.getId(), payment.getOrderId(), ex.toString());
        }
    }
}
