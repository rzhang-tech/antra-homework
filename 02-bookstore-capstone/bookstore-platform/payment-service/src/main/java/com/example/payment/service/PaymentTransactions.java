package com.example.payment.service;

import com.example.payment.entity.Payment;
import com.example.payment.entity.PaymentStatus;
import com.example.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The separately-committed steps, for the same reason as {@code OrderTransactions} in order-service:
 * a saga step has to be durable the moment it completes, and a self-invoked {@code @Transactional}
 * method does not commit at all because Spring AOP is proxy-based.
 */
@Component
@RequiredArgsConstructor
public class PaymentTransactions {

    private final PaymentRepository paymentRepository;

    /**
     * Writes the payment. Commits immediately, so the charge is on record before anything else is
     * attempted — the difference between a payment that can be reconciled and one that cannot.
     */
    @Transactional
    public Payment record(Long orderId, Long userId, BigDecimal amount, PaymentStatus status) {
        return paymentRepository.save(Payment.builder()
                .orderId(orderId)
                .userId(userId)
                .amount(amount)
                .status(status)
                .paidAt(Instant.now())
                .orderNotified(false)
                .build());
    }

    @Transactional
    public void markNotified(Long paymentId) {
        paymentRepository.findById(paymentId).ifPresent(p -> p.setOrderNotified(true));
    }
}
