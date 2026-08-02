package com.example.book.dto;

import com.example.book.entity.Book;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outgoing representation of a book — the API contract, decoupled from the JPA entity so that a schema
 * change does not silently become a breaking API change.
 *
 * <p>The author is flattened to an id and a name rather than nested. A response DTO should carry what a
 * client needs, not mirror the object graph; nesting the whole entity is how lazy-loading errors and
 * accidental over-fetching reach the API.
 */
public record BookResponseDto(
        Long id,
        String title,
        String isbn,
        BigDecimal price,
        Integer stock,
        String coverUrl,
        Long authorId,
        String authorName,
        Instant createdAt
) {

    public static BookResponseDto from(Book book) {
        // Touching book.getAuthor() here resolves the LAZY proxy — one extra query per book when
        // mapping a whole page. Step 2c measures that and fixes it with a fetch join.
        return new BookResponseDto(
                book.getId(),
                book.getTitle(),
                book.getIsbn(),
                book.getPrice(),
                book.getStock(),
                book.getCoverUrl(),
                book.getAuthor() == null ? null : book.getAuthor().getId(),
                book.getAuthor() == null ? null : book.getAuthor().getName(),
                book.getCreatedAt()
        );
    }
}
