package com.example.book.service;

import com.example.book.dto.BookRequestDto;
import com.example.book.dto.BookResponseDto;
import com.example.book.entity.Author;
import com.example.book.entity.Book;
import com.example.book.exception.DuplicateResourceException;
import com.example.book.exception.InsufficientStockException;
import com.example.book.exception.ResourceNotFoundException;
import com.example.book.repository.AuthorRepository;
import com.example.book.repository.BookRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the only class in the project that holds business rules.
 *
 * <p>No Spring context, no database — {@code @ExtendWith(MockitoExtension.class)} creates the mocks and
 * injects them, and the whole class runs in milliseconds. That speed is the point: these are the tests
 * you run on every save, so they must never wait on infrastructure.
 *
 * <p>They can also express situations a real database makes awkward to arrange. "The repository claims
 * this ISBN exists" is one line here; setting it up for real means inserting a row first.
 *
 * <p>What they cannot prove is that the SQL is right, that the mapping matches the schema, or that the
 * transaction actually commits — a mock will happily agree with a query that would fail against
 * PostgreSQL. That is what 4b and 4c are for.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookServiceImpl")
class BookServiceImplTest {

    @Mock
    private BookRepository bookRepository;

    @Mock
    private AuthorRepository authorRepository;

    @Mock
    private com.example.book.repository.StockReservationRepository reservationRepository;

    @InjectMocks
    private BookServiceImpl bookService;

    private static Book book(long id, int stock) {
        return Book.builder()
                .id(id)
                .title("Clean Code")
                .isbn("9780132350884")
                .price(new BigDecimal("42.50"))
                .stock(stock)
                .version(0L)
                .build();
    }

    private static BookRequestDto request(String isbn, Long authorId) {
        return new BookRequestDto("Clean Code", isbn, new BigDecimal("42.50"), 10, authorId);
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("maps the entity to a response DTO")
        void returnsDto() {
            when(bookRepository.findById(1L)).thenReturn(Optional.of(book(1L, 12)));

            BookResponseDto result = bookService.findById(1L);

            assertThat(result.id()).isEqualTo(1L);
            assertThat(result.title()).isEqualTo("Clean Code");
            assertThat(result.stock()).isEqualTo(12);
        }

        @Test
        @DisplayName("throws when the book does not exist")
        void throwsWhenMissing() {
            when(bookRepository.findById(9999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookService.findById(9999L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("9999");
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("saves the book and returns it")
        void savesBook() {
            when(bookRepository.existsByIsbn(anyString())).thenReturn(false);
            when(bookRepository.save(any(Book.class))).thenAnswer(inv -> inv.getArgument(0));

            bookService.create(request("9780132350884", null));

            ArgumentCaptor<Book> saved = ArgumentCaptor.forClass(Book.class);
            verify(bookRepository).save(saved.capture());
            assertThat(saved.getValue().getTitle()).isEqualTo("Clean Code");
            assertThat(saved.getValue().getStock()).isEqualTo(10);
            // The client cannot set these; the database and Hibernate own them.
            assertThat(saved.getValue().getId()).isNull();
            assertThat(saved.getValue().getVersion()).isNull();
        }

        @Test
        @DisplayName("rejects a duplicate ISBN without touching the database")
        void rejectsDuplicateIsbn() {
            when(bookRepository.existsByIsbn("9780132350884")).thenReturn(true);

            assertThatThrownBy(() -> bookService.create(request("9780132350884", null)))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("9780132350884");

            verify(bookRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips the duplicate check when no ISBN is given")
        void allowsMissingIsbn() {
            when(bookRepository.save(any(Book.class))).thenAnswer(inv -> inv.getArgument(0));

            bookService.create(request(null, null));

            verify(bookRepository, never()).existsByIsbn(any());
            verify(bookRepository).save(any(Book.class));
        }

        @Test
        @DisplayName("attaches the author when the id resolves")
        void attachesAuthor() {
            Author author = Author.builder().id(7L).name("Robert C. Martin").build();
            when(bookRepository.existsByIsbn(anyString())).thenReturn(false);
            when(authorRepository.findById(7L)).thenReturn(Optional.of(author));
            when(bookRepository.save(any(Book.class))).thenAnswer(inv -> inv.getArgument(0));

            BookResponseDto result = bookService.create(request("9780132350884", 7L));

            assertThat(result.authorId()).isEqualTo(7L);
            assertThat(result.authorName()).isEqualTo("Robert C. Martin");
        }

        @Test
        @DisplayName("rejects an author id that does not exist rather than dropping it")
        void rejectsUnknownAuthor() {
            when(bookRepository.existsByIsbn(anyString())).thenReturn(false);
            when(authorRepository.findById(9999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookService.create(request("9780132350884", 9999L)))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Author");

            verify(bookRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("mutates the managed entity and never calls save — dirty checking does the write")
        void relaysOnDirtyChecking() {
            Book existing = book(1L, 12);
            when(bookRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(bookRepository.existsByIsbnAndIdNot(anyString(), any())).thenReturn(false);

            bookService.update(1L, new BookRequestDto(
                    "Clean Code 2e", "9780132350884", new BigDecimal("39.99"), 20, null));

            assertThat(existing.getTitle()).isEqualTo("Clean Code 2e");
            assertThat(existing.getStock()).isEqualTo(20);
            // Not an omission being tested — the absence of save() IS the behaviour. Inside a
            // transaction Hibernate flushes the changed fields on commit.
            verify(bookRepository, never()).save(any());
        }

        @Test
        @DisplayName("excludes the book itself from the ISBN duplicate check")
        void excludesSelfFromDuplicateCheck() {
            when(bookRepository.findById(1L)).thenReturn(Optional.of(book(1L, 12)));
            when(bookRepository.existsByIsbnAndIdNot("9780132350884", 1L)).thenReturn(false);

            bookService.update(1L, request("9780132350884", null));

            // existsByIsbn(...) would have matched this very book and made it un-editable.
            verify(bookRepository, never()).existsByIsbn(any());
            verify(bookRepository).existsByIsbnAndIdNot("9780132350884", 1L);
        }

        @Test
        @DisplayName("rejects an ISBN already held by a different book")
        void rejectsIsbnHeldByAnother() {
            when(bookRepository.findById(1L)).thenReturn(Optional.of(book(1L, 12)));
            when(bookRepository.existsByIsbnAndIdNot("9780132350884", 1L)).thenReturn(true);

            assertThatThrownBy(() -> bookService.update(1L, request("9780132350884", null)))
                    .isInstanceOf(DuplicateResourceException.class);
        }
    }

    @Nested
    @DisplayName("purchase")
    class Purchase {

        @Test
        @DisplayName("decrements stock by the quantity bought")
        void decrementsStock() {
            Book existing = book(1L, 12);
            when(bookRepository.findById(1L)).thenReturn(Optional.of(existing));

            BookResponseDto result = bookService.purchase(1L, 5, null);

            assertThat(existing.getStock()).isEqualTo(7);
            assertThat(result.stock()).isEqualTo(7);
        }

        @Test
        @DisplayName("allows buying the entire remaining stock")
        void allowsExactStock() {
            Book existing = book(1L, 3);
            when(bookRepository.findById(1L)).thenReturn(Optional.of(existing));

            bookService.purchase(1L, 3, null);

            assertThat(existing.getStock()).isZero();
        }

        @Test
        @DisplayName("refuses to oversell and leaves stock untouched")
        void refusesOverselling() {
            Book existing = book(1L, 3);
            when(bookRepository.findById(1L)).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> bookService.purchase(1L, 4, null))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("only 3");

            assertThat(existing.getStock()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("loads first, so deleting a missing book is a 404 rather than a silent no-op")
        void loadsBeforeDeleting() {
            Book existing = book(1L, 12);
            when(bookRepository.findById(1L)).thenReturn(Optional.of(existing));

            bookService.delete(1L);

            verify(bookRepository).delete(existing);
        }

        @Test
        @DisplayName("throws when the book does not exist")
        void throwsWhenMissing() {
            when(bookRepository.findById(9999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> bookService.delete(9999L))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(bookRepository, never()).delete(any());
        }
    }
}
