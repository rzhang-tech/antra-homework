package com.example.payment.service;

import com.example.payment.client.OrderGateway;
import com.example.payment.dto.OrderSnapshot;
import com.example.payment.dto.PaymentRequestDto;
import com.example.payment.dto.PaymentResponseDto;
import com.example.payment.entity.Payment;
import com.example.payment.entity.PaymentStatus;
import com.example.payment.exception.OrderServiceUnavailableException;
import com.example.payment.exception.PaymentNotAllowedException;
import com.example.payment.exception.ResourceNotFoundException;
import com.example.payment.repository.PaymentRepository;
import com.example.payment.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentServiceImpl")
class PaymentServiceImplTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentTransactions paymentTransactions;
    @Mock private OrderGateway orders;
    // Step 7c: a successful payment is announced. Mocked - what it publishes is its own test.
    @Mock private com.example.payment.event.PaymentEventPublisher events;

    @InjectMocks private PaymentServiceImpl paymentService;

    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser(7L, "buyer", "USER");
    private static final AuthenticatedUser STRANGER = new AuthenticatedUser(8L, "nosy", "USER");

    private static OrderSnapshot order(String status, String total) {
        return new OrderSnapshot(1L, 7L, status, new BigDecimal(total));
    }

    private static Payment payment(PaymentStatus status, boolean notified) {
        return Payment.builder()
                .id(1L).orderId(1L).userId(7L).amount(new BigDecimal("50.00"))
                .status(status).paidAt(Instant.EPOCH).orderNotified(notified)
                .build();
    }

    private static PaymentRequestDto request() {
        return new PaymentRequestDto(1L);
    }

    @Nested
    @DisplayName("pay")
    class Pay {

        @Test
        @DisplayName("charges the amount order-service reports, never one from the request")
        void chargesTheOrdersAmount() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
            when(orders.findById(1L)).thenReturn(order("AWAITING_PAYMENT", "50.00"));
            when(paymentTransactions.record(1L, 7L, new BigDecimal("50.00"), PaymentStatus.SUCCESS))
                    .thenReturn(payment(PaymentStatus.SUCCESS, false));
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(PaymentStatus.SUCCESS, true)));

            PaymentResponseDto result = paymentService.pay(CUSTOMER, request());

            assertThat(result.amount()).isEqualByComparingTo("50.00");
            verify(paymentTransactions).record(1L, 7L, new BigDecimal("50.00"), PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("tells order-service after charging, and records that it did")
        void notifiesOrderService() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
            when(orders.findById(1L)).thenReturn(order("AWAITING_PAYMENT", "50.00"));
            when(paymentTransactions.record(anyLong(), anyLong(), any(), any()))
                    .thenReturn(payment(PaymentStatus.SUCCESS, false));
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(PaymentStatus.SUCCESS, true)));

            paymentService.pay(CUSTOMER, request());

            verify(orders).markPaid(1L);
            verify(paymentTransactions).markNotified(1L);
        }

        @Test
        @DisplayName("a failure telling order-service does NOT fail the payment — the money is already gone")
        void notificationFailureDoesNotFailThePayment() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
            when(orders.findById(1L)).thenReturn(order("AWAITING_PAYMENT", "50.00"));
            when(paymentTransactions.record(anyLong(), anyLong(), any(), any()))
                    .thenReturn(payment(PaymentStatus.SUCCESS, false));
            when(orders.markPaid(1L))
                    .thenThrow(new OrderServiceUnavailableException("order-service down", null));
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment(PaymentStatus.SUCCESS, false)));

            // Returning an error here would tell a customer their payment failed when it succeeded, and
            // invite them to pay again. The saga rolls forward: the recovery job finishes it.
            PaymentResponseDto result = paymentService.pay(CUSTOMER, request());

            assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(result.orderNotified()).isFalse();
            verify(paymentTransactions, never()).markNotified(anyLong());
        }

        @Test
        @DisplayName("paying twice returns the original payment and charges nothing further")
        void isIdempotent() {
            when(paymentRepository.findByOrderId(1L))
                    .thenReturn(Optional.of(payment(PaymentStatus.SUCCESS, true)));

            PaymentResponseDto result = paymentService.pay(CUSTOMER, request());

            assertThat(result.id()).isEqualTo(1L);
            verify(orders, never()).findById(anyLong());
            verify(paymentTransactions, never()).record(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("a stranger cannot read an existing payment through the idempotent fast path")
        void fastPathStillChecksOwnership() {
            when(paymentRepository.findByOrderId(1L))
                    .thenReturn(Optional.of(payment(PaymentStatus.SUCCESS, true)));

            // The bug this pins: the fast path added for idempotency skipped the ownership check the
            // slow path performs, so any authenticated user could confirm an order existed and read
            // its amount. Every test of the slow path still passed.
            assertThatThrownBy(() -> paymentService.pay(STRANGER, request()))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a stranger cannot pay for someone else's order")
        void rejectsAnotherCustomersOrder() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
            when(orders.findById(1L)).thenReturn(order("AWAITING_PAYMENT", "50.00"));

            assertThatThrownBy(() -> paymentService.pay(STRANGER, request()))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(paymentTransactions, never()).record(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("an order not awaiting payment cannot be paid for")
        void rejectsWrongOrderState() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
            when(orders.findById(1L)).thenReturn(order("PENDING", "50.00"));

            assertThatThrownBy(() -> paymentService.pay(CUSTOMER, request()))
                    .isInstanceOf(PaymentNotAllowedException.class)
                    .hasMessageContaining("PENDING");

            // A PENDING order's stock was never confirmed reserved. Taking money for it would be
            // recording a sale that may not have happened.
            verify(paymentTransactions, never()).record(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("a declined charge is recorded as FAILED and reported as 409")
        void recordsDeclines() {
            when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.empty());
            when(orders.findById(1L)).thenReturn(order("AWAITING_PAYMENT", "10.13"));
            when(paymentTransactions.record(1L, 7L, new BigDecimal("10.13"), PaymentStatus.FAILED))
                    .thenReturn(payment(PaymentStatus.FAILED, false));

            assertThatThrownBy(() -> paymentService.pay(CUSTOMER, request()))
                    .isInstanceOf(PaymentNotAllowedException.class)
                    .hasMessageContaining("declined");

            // Recorded even though it failed: a decline is a fact about the order worth keeping, and
            // without the row a retry would look like a first attempt.
            verify(paymentTransactions).record(1L, 7L, new BigDecimal("10.13"), PaymentStatus.FAILED);
            verify(orders, never()).markPaid(anyLong());
        }
    }

    @Nested
    @DisplayName("findByOrderId")
    class Find {

        @Test
        @DisplayName("the payer can read their own payment")
        void ownerCanRead() {
            when(paymentRepository.findByOrderId(1L))
                    .thenReturn(Optional.of(payment(PaymentStatus.SUCCESS, true)));

            assertThat(paymentService.findByOrderId(CUSTOMER, 1L).orderId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("an admin can read anyone's payment")
        void adminCanRead() {
            when(paymentRepository.findByOrderId(1L))
                    .thenReturn(Optional.of(payment(PaymentStatus.SUCCESS, true)));

            assertThat(paymentService
                    .findByOrderId(new AuthenticatedUser(1L, "admin", "ADMIN"), 1L)
                    .orderId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("anyone else gets 404 — a 403 would confirm the payment exists")
        void strangerGetsNotFound() {
            when(paymentRepository.findByOrderId(1L))
                    .thenReturn(Optional.of(payment(PaymentStatus.SUCCESS, true)));

            assertThatThrownBy(() -> paymentService.findByOrderId(STRANGER, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
