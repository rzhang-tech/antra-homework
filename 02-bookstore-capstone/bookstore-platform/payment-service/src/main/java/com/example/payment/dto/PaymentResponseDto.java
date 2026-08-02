package com.example.payment.dto;

import com.example.payment.entity.Payment;
import com.example.payment.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponseDto(
        Long id,
        Long orderId,
        BigDecimal amount,
        PaymentStatus status,
        Instant paidAt,
        boolean orderNotified
) {

    public static PaymentResponseDto from(Payment payment) {
        return new PaymentResponseDto(
                payment.getId(),
                payment.getOrderId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getPaidAt(),
                payment.isOrderNotified());
    }
}
