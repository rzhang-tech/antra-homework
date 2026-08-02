package com.example.book.dto;

import com.example.book.entity.Author;
import com.example.book.entity.Book;

import java.math.BigDecimal;
import java.util.List;

/**
 * An author together with the books attributed to them.
 *
 * <p>The nested books are summaries, not full {@link BookResponseDto}s: a book inside an author does not
 * need to repeat the author. Left as the full DTO it would carry {@code authorName} on every element,
 * and a bidirectional entity graph serialized naively is how infinite recursion happens.
 */
public record AuthorResponseDto(
        Long id,
        String name,
        List<BookSummary> books
) {

    public record BookSummary(Long id, String title, BigDecimal price) {
        static BookSummary from(Book book) {
            return new BookSummary(book.getId(), book.getTitle(), book.getPrice());
        }
    }

    /** Reads {@code author.getBooks()} — one extra query per author unless it was fetched eagerly. */
    public static AuthorResponseDto from(Author author) {
        return new AuthorResponseDto(
                author.getId(),
                author.getName(),
                author.getBooks().stream().map(BookSummary::from).toList()
        );
    }
}
