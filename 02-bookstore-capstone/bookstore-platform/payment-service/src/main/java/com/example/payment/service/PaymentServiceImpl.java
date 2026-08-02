package com.example.payment.service;

import com.example.payment.client.OrderGateway;
import com.example.payment.dto.OrderSnapshot;
import com.example.payment.dto.PaymentRequestDto;
import com.example.payment.dto.PaymentResponseDto;
import com.example.payment.entity.Payment;
import com.example.payment.entity.PaymentStatus;
import com.example.payment.exception.PaymentNotAllowedException;
import com.example.payment.exception.ResourceNotFoundException;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentTransactions paymentTransactions;
    private final OrderGateway orders;

    /**
     * Pays for an order.
     *
     * <p><strong>This saga rolls forward, and that is the point of the step.</strong> Placing an order
     * unwinds on failure: releasing a reservation is cheap and leaves no trace. Once money has moved,
     * unwinding means issuing a refund — slower, visible to the customer, and in a real system
     * chargeable. So when the charge has succeeded and telling order-service fails, the correct
     * response is to keep trying to finish, not to undo.
     *
     * <p>Compensation direction is a business decision, not a technical one. The same code shape
     * supports both; which way it points depends on what the steps cost to reverse.
     *
     * <ol>
     *   <li><strong>Read the order</strong> and check it is the caller's and awaiting payment. Free.</li>
     *   <li><strong>Charge and record, in one transaction.</strong> The UNIQUE constraint on
     *       {@code order_id} settles any duplicate here, whatever caused it.</li>
     *   <li><strong>Tell order-service,</strong> retried. If it fails the payment still stands, marked
     *       un-notified, and {@link PaymentRecoveryJob} finishes the job later.</li>
     * </ol>
     */
    @Override
    public PaymentResponseDto pay(AuthenticatedUser customer, PaymentRequestDto request) {
        Long orderId = request.orderId();

        // Idempotency, first pass. The constraint below is the real guarantee — this is the cheap,
        // common case, and it gives the caller the original result rather than an error.
        var existing = paymentRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            Payment payment = existing.get();

            /*
             * Ownership is checked HERE, not only on the path below.
             *
             * The first version returned early without it, on the reasoning that the ownership check
             * happens after reading the order. It does — on the path that reads the order, which this
             * one skips. So once an order had been paid for, ANY authenticated user asking to pay for
             * it got 201 and the payment details back: an amount, a timestamp, and confirmation that
             * the order exists.
             *
             * The general shape is worth remembering: a fast path added for idempotency skipped a
             * check the slow path performed, and the tests for the slow path all still passed.
             */
            if (!payment.getUserId().equals(customer.id())) {
                throw new ResourceNotFoundException("Order not found with id " + orderId);
            }

            log.info("Order {} is already paid (payment {}); returning it unchanged",
                    orderId, payment.getId());
            notifyOrderService(payment);   // may still be outstanding from a previous attempt
            return PaymentResponseDto.from(payment);
        }

        // --- 1. Read and validate. --------------------------------------------------------------
        OrderSnapshot order = orders.findById(orderId);
        if (order == null || order.id() == null) {
            throw new ResourceNotFoundException("Order not found with id " + orderId);
        }
        if (!order.userId().equals(customer.id())) {
            // 404 rather than 403: telling a stranger the order exists lets them enumerate orders.
            throw new ResourceNotFoundException("Order not found with id " + orderId);
        }
        if (!"AWAITING_PAYMENT".equals(order.status())) {
            throw new PaymentNotAllowedException(
                    "Order " + orderId + " is " + order.status() + " and cannot be paid for");
        }

        // --- 2. Charge, and record it. ----------------------------------------------------------
        Payment payment;
        try {
            payment = paymentTransactions.record(orderId, customer.id(), order.totalPrice(),
                    charge(orderId, order));
        } catch (DataIntegrityViolationException ex) {
            // Two requests raced past the check above. The constraint decided; return the winner's
            // result rather than an error, because from the caller's point of view it worked.
            log.info("Concurrent payment for order {}; returning the one that won", orderId);
            payment = paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> ex);
            if (!payment.getUserId().equals(customer.id())) {
                throw new ResourceNotFoundException("Order not found with id " + orderId);
            }
            notifyOrderService(payment);
            return PaymentResponseDto.from(payment);
        }

        if (payment.getStatus() == PaymentStatus.FAILED) {
            throw new PaymentNotAllowedException("Payment for order " + orderId + " was declined");
        }

        // --- 3. Tell order-service. Failure here does not undo anything. ------------------------
        notifyOrderService(payment);

        return PaymentResponseDto.from(paymentRepository.findById(payment.getId()).orElse(payment));
    }

    /**
     * Stands in for a payment gateway.
     *
     * <p>There is no real one, and pretending otherwise would teach nothing. What matters for the saga
     * is that this step can fail and cannot be undone for free — so it declines any amount ending in
     * .13, which gives the failure path something to exercise without a card network.
     */
    private PaymentStatus charge(Long orderId, OrderSnapshot order) {
        boolean declined = order.totalPrice().remainder(java.math.BigDecimal.ONE)
                .compareTo(new java.math.BigDecimal("0.13")) == 0;
        if (declined) {
            log.warn("Simulated decline for order {} (amount {})", orderId, order.totalPrice());
            return PaymentStatus.FAILED;
        }
        return PaymentStatus.SUCCESS;
    }

    /**
     * Tells order-service the money arrived, and records that we did.
     *
     * <p>Swallows its failure on purpose. The customer has been charged successfully; returning an error
     * because a downstream status update failed would tell them their payment did not work when it did,
     * and invite them to pay twice. The payment stays flagged un-notified and the recovery job retries.
     */
    private void notifyOrderService(Payment payment) {
        if (payment.isOrderNotified() || payment.getStatus() != PaymentStatus.SUCCESS) {
            return;
        }
        try {
            orders.markPaid(payment.getOrderId());
            paymentTransactions.markNotified(payment.getId());
        } catch (RuntimeException ex) {
            log.error("Payment {} for order {} succeeded but order-service could not be told ({}). "
                            + "Leaving it un-notified for the recovery job — the customer HAS been "
                            + "charged and must not be asked to pay again.",
                    payment.getId(), payment.getOrderId(), ex.toString());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponseDto findByOrderId(AuthenticatedUser caller, Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No payment for order " + orderId));

        boolean isOwner = payment.getUserId().equals(caller.id());
        boolean isAdmin = "ADMIN".equals(caller.role());
        if (!isOwner && !isAdmin) {
            throw new ResourceNotFoundException("No payment for order " + orderId);
        }
        return PaymentResponseDto.from(payment);
    }
}
