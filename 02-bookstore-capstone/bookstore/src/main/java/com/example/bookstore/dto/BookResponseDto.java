package com.example.bookstore.dto;

import com.example.bookstore.entity.Book;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outgoing representation of a book — the API contract, decoupled from the JPA entity so that a schema
 * change does not silently become a breaking API change.
 */
public record BookResponseDto(
        Long id,
        String title,
        String isbn,
        BigDecimal price,
        Integer stock,
        String coverUrl,
        Instant createdAt
) {

    public static BookResponseDto from(Book book) {
        return new BookResponseDto(
                book.getId(),
                book.getTitle(),
                book.getIsbn(),
                book.getPrice(),
                book.getStock(),
                book.getCoverUrl(),
                book.getCreatedAt()
        );
    }
}
