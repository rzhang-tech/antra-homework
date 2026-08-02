package com.example.book.dto;

import java.time.Instant;

/**
 * One entry in "recently viewed".
 *
 * <p>Carries the title as it was <em>at the time of viewing</em>, copied into the DynamoDB item rather
 * than looked up when the history is read. The same reasoning as {@code order_item.unit_price} in Step
 * 5b, for a different reason: not correctness but availability and cost. Rendering ten history entries
 * would otherwise be ten primary-key reads against PostgreSQL on a page nobody considers important, and
 * a deleted book would leave a hole in a list that is only ever informational.
 *
 * @param bookId   still the useful part - a client links to the book with this
 * @param title    a snapshot, possibly stale, deliberately
 * @param viewedAt when it was viewed, in UTC
 */
public record BookViewDto(Long bookId, String title, Instant viewedAt) {
}
