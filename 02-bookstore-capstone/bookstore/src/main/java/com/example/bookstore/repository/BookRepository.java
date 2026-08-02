package com.example.bookstore.repository;

import com.example.bookstore.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    /**
     * Case-insensitive keyword search over the title, paged.
     *
     * <p><strong>Written out rather than derived from the method name, on purpose.</strong> The derived
     * form — {@code findByTitleContainingIgnoreCase} — generates {@code UPPER(title) LIKE UPPER(?)},
     * while the trigram index in {@code V3__add_book_indexes.sql} is built on {@code lower(title)}. An
     * expression index is only used when the query's expression matches it character for character, so
     * the derived query silently fell back to a sequential scan: 21.9 ms against 0.175 ms on 100k rows,
     * with the index sitting there unused and no warning anywhere.
     *
     * <p>Spelling the query out keeps the SQL and the index under the same control and visibly aligned.
     * The alternative — building the index on {@code upper(title)} to match — works, but leaves the
     * index depending on a code-generation detail that a Spring Data upgrade could change.
     */
    @EntityGraph(attributePaths = "author")
    @Query("SELECT b FROM Book b WHERE LOWER(b.title) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Book> searchByTitle(@Param("keyword") String keyword, Pageable pageable);

    /**
     * {@code @EntityGraph} tells Hibernate to load {@code author} in the <em>same</em> query as the
     * book, as a LEFT JOIN, instead of leaving the LAZY proxy for someone to trip over later. Same
     * effect as writing {@code LEFT JOIN FETCH} by hand, but it composes with a derived query and with
     * {@code Pageable}.
     *
     * <p>Safe to combine with paging here only because {@code author} is a to-<em>one</em> association:
     * joining it cannot multiply the number of book rows, so LIMIT/OFFSET still means what it says. The
     * same trick on a to-many association is a trap — see {@link AuthorRepository#findAllWithBooks()}.
     */
    @EntityGraph(attributePaths = "author")
    @Override
    Page<Book> findAll(Pageable pageable);

    @EntityGraph(attributePaths = "author")
    @Override
    Optional<Book> findById(Long id);

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, Long id);
}
