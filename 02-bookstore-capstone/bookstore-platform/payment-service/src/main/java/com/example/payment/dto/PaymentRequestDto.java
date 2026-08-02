package com.example.payment.dto;

import jakarta.validation.constraints.NotNull;

/**
 * A request to pay for an order.
 *
 * <p>One field, and the absence of the others is the design. No amount — that comes from order-service,
 * because a client naming its own price is not a payment system. No user id — that comes from the token.
 * No card details either: this service records that a payment happened and never sees an instrument.
 */
public record PaymentRequestDto(

        @NotNull(message = "orderId is required")
        Long orderId
) {
}
