package com.example.book.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Incoming payload for creating or updating a book.
 *
 * <p>Deliberately does <em>not</em> expose {@code id}, {@code version}, or {@code createdAt}: those are
 * server-owned. Binding a request body straight onto the entity would let a client overwrite them.
 */
public record BookRequestDto(

        @NotBlank(message = "title is required")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @Size(max = 20, message = "isbn must be at most 20 characters")
        String isbn,

        @NotNull(message = "price is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "price must be greater than 0")
        BigDecimal price,

        @NotNull(message = "stock is required")
        @PositiveOrZero(message = "stock cannot be negative")
        Integer stock,

        /**
         * The author's id, or null for a book with no author on record. A client sends the id rather
         * than a nested author object: creating a book must not silently create an author.
         */
        Long authorId
) {
}
