package com.example.bookstore.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A book in the catalog.
 *
 * <p>The database index on {@code title} arrives in Step 2d.
 */
@Entity
@Table(name = "book")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Book {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(unique = true)
    private String isbn;

    /**
     * Many books, one author.
     *
     * <p>{@code LAZY} means Hibernate does not join to {@code author} when loading a book; it stores a
     * placeholder and fetches the row only if someone actually reads the field. That is the right
     * default — a join you did not ask for is wasted work on every query that never touches the author.
     *
     * <p>The cost is that reading {@code getAuthor().getName()} for a page of books fires one extra
     * query <em>per book</em>. That is the N+1 problem, and Step 2c reproduces and fixes it here.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    private Author author;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer stock;

    /** S3 URL, populated by the cover-upload pipeline in Step 9. */
    @Column(name = "cover_url")
    private String coverUrl;

    /**
     * Optimistic-locking token. Hibernate increments it on every update and fails the write if another
     * transaction already moved it — which is how two concurrent orders cannot both sell the last copy.
     *
     * <p>The column is {@code NOT NULL DEFAULT 0} as of V5. Hibernate initialises it for entities it
     * creates, but a row inserted by plain SQL used to land with NULL, and the first update of such a
     * row then failed on the increment.
     */
    @Version
    @Column(nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
