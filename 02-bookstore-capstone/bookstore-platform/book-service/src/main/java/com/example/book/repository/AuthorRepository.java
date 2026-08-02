package com.example.book.repository;

import com.example.book.entity.Author;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AuthorRepository extends JpaRepository<Author, Long> {

    /**
     * Authors together with their books, in a single query.
     *
     * <p>{@code LEFT JOIN FETCH} is the JPQL way to say "load this association as part of this query".
     * {@code LEFT} rather than an inner join so that an author with no books still appears.
     *
     * <p>{@code DISTINCT} is needed because the join multiplies rows: an author with three books comes
     * back as three rows, and without it Hibernate would hand back the same Author object three times.
     * The database rows are still duplicated — DISTINCT here de-duplicates the <em>entities</em>.
     *
     * <p><strong>Why this method returns a List and not a Page.</strong> Fetching a to-many association
     * and paginating cannot both be done in SQL: LIMIT applies to joined rows, not to authors, so
     * "10 per page" would cut an author's books in half. Hibernate detects this and silently falls back
     * to loading <em>every</em> row and paginating in memory, warning
     * {@code HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory}.
     * That is a production incident waiting to happen on a large table. Options when you genuinely need
     * both are {@code @BatchSize}, or two queries (page the ids, then fetch the collections for them).
     * The author list here is small and unpaged, so a plain List is the honest choice.
     */
    @Query("SELECT DISTINCT a FROM Author a LEFT JOIN FETCH a.books ORDER BY a.id")
    List<Author> findAllWithBooks();
}
