package com.example.book.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record PurchaseRequestDto(

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than 0")
        Integer quantity,

        /**
         * An id the caller chooses so this request can be repeated safely.
         *
         * <p>Optional, and that is a compatibility decision rather than an endorsement. Without one the
         * call is not idempotent and a retry after a lost response decrements stock twice — so any
         * caller that retries (order-service does) must supply one. A human buying a book from a
         * request file need not.
         *
         * <p>Supplying the same id twice returns the same result and takes no further stock.
         */
        UUID reservationId
) {
}
