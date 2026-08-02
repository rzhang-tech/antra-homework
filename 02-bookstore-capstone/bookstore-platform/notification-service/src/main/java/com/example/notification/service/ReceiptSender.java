package com.example.notification.service;

import com.example.notification.event.PaymentCompleted;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Sends the customer their receipt.
 *
 * <p>Its own guard instance rather than sharing {@link ProcessedOrders} with {@link ConfirmationSender},
 * and the reason is worth stating: a confirmation and a receipt are two different pieces of work about
 * the same order. Sharing one "have I seen order 17?" set would mean confirming an order suppressed the
 * receipt for it. <strong>Idempotency keys identify a unit of work, not an entity.</strong>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReceiptSender {

    private final ProcessedPayments processed;

    public void send(PaymentCompleted event) {
        if (!processed.firstTimeSeeing(event.orderId())) {
            return;
        }

        log.info("RECEIPT to user {}: {} charged for order {} (payment {}, at {})",
                event.userId(), event.amount(), event.orderId(), event.paymentId(), event.paidAt());
    }
}
