package com.example.bookstore.repository;

import com.example.bookstore.entity.Book;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookRepository extends JpaRepository<Book, Long> {

    /**
     * Keyword search over the title, paged. Spring Data derives the query from the method name:
     * {@code WHERE lower(title) LIKE lower('%' || :keyword || '%')}.
     *
     * <p>Note the leading wildcard — it prevents a plain B-tree index on {@code title} from being used.
     * Step 2 revisits this with EXPLAIN ANALYZE and a full-text / trigram index.
     */
    Page<Book> findByTitleContainingIgnoreCase(String keyword, Pageable pageable);

    boolean existsByIsbn(String isbn);

    boolean existsByIsbnAndIdNot(String isbn, Long id);
}
