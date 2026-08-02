package com.example.payment.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * What payment-service needs to know about an order.
 *
 * <p>Four fields out of the six order-service returns, with the rest ignored rather than causing a
 * deserialization failure — so order-service may add fields without breaking payments. Same
 * consumer-driven shape as {@code BookSnapshot} in order-service (D12).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSnapshot(
        Long id,
        Long userId,
        String status,
        BigDecimal totalPrice
) {
}
