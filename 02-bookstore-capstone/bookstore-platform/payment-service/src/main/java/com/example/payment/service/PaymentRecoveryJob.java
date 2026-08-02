package com.example.payment.service;

import com.example.payment.client.OrderGateway;
import com.example.payment.entity.Payment;
import com.example.payment.entity.PaymentStatus;
import com.example.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Finishes payments whose second write never landed.
 *
 * <p>The mirror image of {@code OrderRecoveryJob}, and the contrast is the lesson. That job
 * <em>unwinds</em> stranded orders, because releasing a reservation is cheap and invisible. This one
 * <em>completes</em> stranded payments, because the money has already moved and undoing it means a
 * refund — slower, visible to the customer, and in a real system chargeable.
 *
 * <p>Same mechanism, opposite direction. Which way a saga points is a business decision about the cost
 * of reversing each step, not a property of the pattern.
 *
 * <p>A payment that is SUCCESS but not {@code orderNotified} is the footprint: the customer has paid and
 * their order does not know. Left alone that is the worst outcome available — charged, with nothing to
 * show for it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentRecoveryJob {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactions paymentTransactions;
    private final OrderGateway orders;

    @Scheduled(fixedDelayString = "${app.saga.recovery-interval-ms:30000}")
    public void notifyOutstandingPayments() {
        List<Payment> outstanding =
                paymentRepository.findByStatusAndOrderNotifiedFalse(PaymentStatus.SUCCESS);

        if (outstanding.isEmpty()) {
            return;
        }

        log.warn("Payment recovery: {} successful payment(s) order-service has not been told about",
                outstanding.size());

        for (Payment payment : outstanding) {
            try {
                orders.markPaid(payment.getOrderId());
                paymentTransactions.markNotified(payment.getId());
                log.info("Payment recovery: order {} marked paid from payment {}",
                        payment.getOrderId(), payment.getId());
            } catch (RuntimeException ex) {
                // No backoff limit and no giving up. There is no acceptable resting state for
                // "customer charged, order unaware", so this keeps trying until it succeeds or a human
                // intervenes. The alternative — marking it resolved after N attempts — would mean
                // quietly deciding to keep the money.
                log.error("Payment recovery: order {} still cannot be marked paid ({}). Will retry.",
                        payment.getOrderId(), ex.toString());
            }
        }
    }
}
