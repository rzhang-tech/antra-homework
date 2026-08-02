package com.example.bookstore.repository;

import com.example.bookstore.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {

    /**
     * Keyword search over the title, paged. Spring Data derives the query from the method name:
     * {@code WHERE lower(title) LIKE lower('%' || :keyword || '%')}.
     *
     * <p>Note the leading wildcard — it prevents a plain B-tree index on {@code title} from being used.
     * Step 2d revisits this with EXPLAIN ANALYZE.
     */
    @EntityGraph(attributePaths = "author")
    Page<Book> findByTitleContainingIgnoreCase(String keyword, Pageable pageable);

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
