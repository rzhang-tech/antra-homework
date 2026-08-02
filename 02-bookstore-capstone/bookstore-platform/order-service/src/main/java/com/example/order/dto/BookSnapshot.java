package com.example.order.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * What order-service needs to know about a book, and nothing more.
 *
 * <p>book-service returns eight fields; this declares four. {@code @JsonIgnoreProperties(ignoreUnknown)}
 * makes the rest arrive and be discarded rather than causing a deserialization failure — so book-service
 * can add a field tomorrow without breaking orders. Consumer-driven contracts in the small: each
 * consumer declares what it depends on, and depends on nothing else.
 *
 * <p>"Snapshot" is the important word. This is what the price and stock were <em>at the moment of the
 * call</em>. By the time the order is written they may both have changed — another customer buying the
 * last copy in between is not an edge case, it is Tuesday. The price is therefore copied onto the order
 * line rather than referenced.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookSnapshot(
        Long id,
        String title,
        BigDecimal price,
        Integer stock
) {
}
