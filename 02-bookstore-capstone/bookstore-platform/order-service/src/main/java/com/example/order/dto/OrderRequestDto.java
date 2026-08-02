package com.example.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * What a client sends to place an order.
 *
 * <p>Note what is absent: {@code userId}, {@code unitPrice} and {@code totalPrice}. The customer comes
 * from the token, and every price is read from book-service. A client that could name its own price
 * would be a shop that lets you write your own receipt.
 */
public record OrderRequestDto(

        @NotEmpty(message = "an order needs at least one item")
        @Size(max = 50, message = "an order may contain at most 50 lines")
        @Valid
        List<OrderItemRequestDto> items
) {
}
