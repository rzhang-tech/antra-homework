package com.example.book.repository;

import com.example.book.TestcontainersConfig;
import com.example.book.entity.Author;
import com.example.book.entity.Book;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The persistence slice, against a real PostgreSQL.
 *
 * <p>{@code @DataJpaTest} loads the entities, the repositories, and a transaction manager — no
 * controllers, no security, no services. It also rolls back after every test, so the methods cannot
 * contaminate each other regardless of what they write.
 *
 * <p>{@code @AutoConfigureTestDatabase(replace = NONE)} is essential: by default this slice swaps in an
 * embedded database, which would silently undo the entire point. The tests below check things only the
 * real engine can answer — that Flyway's DDL matches the entity mapping, that the derived and custom
 * queries are valid SQL, and that the constraints in the migrations actually fire.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@DisplayName("BookRepository (real PostgreSQL)")
class BookRepositoryTest {

    @Autowired private BookRepository bookRepository;
    @Autowired private AuthorRepository authorRepository;
    @Autowired private EntityManager entityManager;

    private Author martin;

    @BeforeEach
    void seed() {
        martin = authorRepository.save(Author.builder().name("Robert C. Martin").build());
        Author bloch = authorRepository.save(Author.builder().name("Joshua Bloch").build());

        bookRepository.saveAll(List.of(
                book("Clean Code", "111", martin),
                book("Clean Architecture", "222", martin),
                book("Effective Java", "333", bloch),
                book("Java Puzzlers", "444", bloch)));

        // Force the INSERTs to hit the database now, and clear the persistence context so the reads
        // below come from PostgreSQL rather than from Hibernate's first-level cache. Without this a
        // broken query can still "pass" by returning the object the test just put in memory.
        entityManager.flush();
        entityManager.clear();
    }

    private static Book book(String title, String isbn, Author author) {
        return Book.builder()
                .title(title).isbn(isbn)
                .price(new BigDecimal("42.50")).stock(10)
                .author(author)
                .build();
    }

    @Test
    @DisplayName("Flyway's schema matches the entity mapping")
    void schemaMatchesEntities() {
        // ddl-auto: validate means the context would have failed to start on any mismatch — a column
        // renamed in a migration but not in the entity, a type that does not line up. Reaching this
        // assertion at all is the real result.
        assertThat(bookRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("searchByTitle is case-insensitive and matches a substring")
    void searchIsCaseInsensitiveSubstring() {
        Page<Book> result = bookRepository.searchByTitle("clean", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(Book::getTitle)
                .containsExactlyInAnyOrder("Clean Code", "Clean Architecture");
    }

    @Test
    @DisplayName("searchByTitle matches mid-word, not just a prefix")
    void searchMatchesMidWord() {
        Page<Book> result = bookRepository.searchByTitle("ava", PageRequest.of(0, 10));

        assertThat(result.getContent())
                .extracting(Book::getTitle)
                .containsExactlyInAnyOrder("Effective Java", "Java Puzzlers");
    }

    @Test
    @DisplayName("searchByTitle pages correctly")
    void searchPages() {
        Page<Book> first = bookRepository.searchByTitle("a", PageRequest.of(0, 2));

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isEqualTo(4);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.isLast()).isFalse();
    }

    /*
     * The next two assert on save-then-flush as one action rather than on flush alone.
     *
     * With @GeneratedValue(strategy = IDENTITY), Hibernate cannot defer the INSERT: it needs the
     * database-generated key immediately, so persist() executes the statement then and there and the
     * constraint fires inside save(). Under a sequence or table generator the same code would defer to
     * flush. Asserting on the pair keeps the test about the constraint rather than about the id
     * strategy — a first version that only wrapped flush() failed for exactly this reason.
     */

    @Test
    @DisplayName("the unique constraint on isbn is enforced by the database, not just by the service")
    void isbnIsUniqueInTheDatabase() {
        Book duplicate = book("Duplicate", "111", martin);

        assertThatThrownBy(() -> {
            bookRepository.save(duplicate);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK (stock >= 0) rejects negative stock even when the service is bypassed")
    void negativeStockIsRejected() {
        Book negative = book("Negative", "555", martin);
        negative.setStock(-1);

        // Bean Validation on the DTO never ran here — this is the migration's constraint doing the work,
        // which is the whole argument for having both layers.
        assertThatThrownBy(() -> {
            bookRepository.save(negative);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("version defaults to 0 for a row inserted outside Hibernate")
    void versionDefaultsToZero() {
        // The V5 regression, pinned. A plain SQL INSERT with no version used to leave NULL, and the
        // first update of that row then failed on the increment.
        entityManager.createNativeQuery("""
                INSERT INTO book (title, isbn, price, stock, created_at)
                VALUES ('Raw Insert', '999', 9.99, 5, NOW())
                """).executeUpdate();
        entityManager.clear();

        Book found = bookRepository.findAll().stream()
                .filter(b -> "999".equals(b.getIsbn()))
                .findFirst()
                .orElseThrow();

        assertThat(found.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("existsByIsbnAndIdNot ignores the book being edited")
    void existsByIsbnAndIdNotExcludesSelf() {
        Book cleanCode = bookRepository.findAll().stream()
                .filter(b -> "111".equals(b.getIsbn()))
                .findFirst()
                .orElseThrow();

        assertThat(bookRepository.existsByIsbn("111")).isTrue();
        assertThat(bookRepository.existsByIsbnAndIdNot("111", cleanCode.getId())).isFalse();
        assertThat(bookRepository.existsByIsbnAndIdNot("111", cleanCode.getId() + 1000)).isTrue();
    }

    @Test
    @DisplayName("findAll fetches the author in the same query — the @EntityGraph works")
    void entityGraphFetchesAuthor() {
        Page<Book> page = bookRepository.findAll(PageRequest.of(0, 10));
        entityManager.clear();   // detach everything, so a lazy proxy could no longer be resolved

        // If the author were still a lazy proxy, reading it after clear() would throw
        // LazyInitializationException. It does not, because the @EntityGraph joined it in.
        assertThat(page.getContent())
                .allSatisfy(book -> assertThat(book.getAuthor().getName()).isNotBlank());
    }
}
