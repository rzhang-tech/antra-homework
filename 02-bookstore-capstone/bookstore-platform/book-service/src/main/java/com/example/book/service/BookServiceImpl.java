package com.example.book.service;

import com.example.book.dto.BookRequestDto;
import com.example.book.dto.BookResponseDto;
import com.example.book.dto.PageResponseDto;
import com.example.book.entity.Author;
import com.example.book.entity.Book;
import com.example.book.entity.StockReservation;
import com.example.book.exception.DuplicateResourceException;
import com.example.book.exception.InsufficientStockException;
import com.example.book.exception.ResourceNotFoundException;
import com.example.book.repository.AuthorRepository;
import com.example.book.repository.BookRepository;
import com.example.book.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookServiceImpl implements BookService {

    /**
     * Constructor injection (via Lombok's {@code @RequiredArgsConstructor} on a final field) rather than
     * {@code @Autowired} on the field: the dependency is immutable, impossible to forget, and can be
     * passed in directly by a unit test with no Spring context.
     */
    private final BookRepository bookRepository;
    private final AuthorRepository authorRepository;
    private final StockReservationRepository reservationRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponseDto<BookResponseDto> findAll(String keyword, Pageable pageable) {
        Page<Book> books = StringUtils.hasText(keyword)
                ? bookRepository.searchByTitle(keyword.trim(), pageable)
                : bookRepository.findAll(pageable);
        return PageResponseDto.from(books, BookResponseDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public BookResponseDto findById(Long id) {
        return BookResponseDto.from(getOrThrow(id));
    }

    @Override
    @Transactional
    public BookResponseDto create(BookRequestDto request) {
        if (StringUtils.hasText(request.isbn()) && bookRepository.existsByIsbn(request.isbn())) {
            throw new DuplicateResourceException("A book with isbn " + request.isbn() + " already exists");
        }
        Book book = Book.builder()
                .title(request.title())
                .isbn(request.isbn())
                .price(request.price())
                .stock(request.stock())
                .author(resolveAuthor(request.authorId()))
                .build();
        return BookResponseDto.from(bookRepository.save(book));
    }

    @Override
    @Transactional
    public BookResponseDto update(Long id, BookRequestDto request) {
        Book book = getOrThrow(id);
        if (StringUtils.hasText(request.isbn())
                && bookRepository.existsByIsbnAndIdNot(request.isbn(), id)) {
            throw new DuplicateResourceException("A book with isbn " + request.isbn() + " already exists");
        }
        book.setTitle(request.title());
        book.setIsbn(request.isbn());
        book.setPrice(request.price());
        book.setStock(request.stock());
        book.setAuthor(resolveAuthor(request.authorId()));
        // No explicit save() call: `book` is a managed entity inside this transaction, so Hibernate
        // flushes the changes on commit (dirty checking).
        return BookResponseDto.from(book);
    }

    /**
     * Read stock, check it, write it back — a classic read-modify-write.
     *
     * <p>{@code @Transactional} makes the three steps atomic against a crash, but on its own it does
     * <em>not</em> stop two concurrent transactions from both reading stock = 1 and both writing 0.
     * That is a lost update, and PostgreSQL's default READ COMMITTED isolation permits it.
     *
     * <p>What stops it is the {@code @Version} column. Hibernate emits
     * {@code UPDATE book SET stock = ?, version = 6 WHERE id = ? AND version = 5} — so the second
     * transaction to commit matches zero rows, Hibernate raises an optimistic-lock failure, and the
     * transaction rolls back. The caller gets a 409 and can retry against the now-current stock.
     *
     * <p>Optimistic rather than pessimistic ({@code SELECT ... FOR UPDATE}) because conflicts on a book
     * are rare: it costs nothing in the common case and only makes the loser retry. A pessimistic lock
     * serialises every purchase of the same book whether or not anyone is competing for it.
     */
    @Override
    @Transactional
    public BookResponseDto purchase(Long id, int quantity, UUID reservationId) {
        /*
         * The idempotency check, and the reason this endpoint can now be retried.
         *
         * Seeing a reservation id we have already recorded means this exact request reached us before
         * — almost certainly because our previous response never got back to the caller. Reporting the
         * current state without decrementing again is the whole mechanism.
         *
         * Note it deliberately does NOT verify that the quantity matches. A caller reusing an id with a
         * different quantity has a bug, and the safe reading of "I already did this" is to do nothing.
         */
        if (reservationId != null) {
            Optional<StockReservation> existing = reservationRepository.findById(reservationId);
            if (existing.isPresent()) {
                log.info("Reservation {} already applied; returning current state without decrementing",
                        reservationId);
                return BookResponseDto.from(getOrThrow(id));
            }
        }

        Book book = getOrThrow(id);
        if (book.getStock() < quantity) {
            throw new InsufficientStockException(id, quantity, book.getStock());
        }
        book.setStock(book.getStock() - quantity);

        if (reservationId != null) {
            /*
             * Written in the SAME transaction as the decrement. That is not tidiness — it is what makes
             * the guarantee hold. Two separate transactions could commit the decrement and lose the
             * record, and a subsequent retry would then decrement a second time, which is exactly the
             * bug this exists to prevent.
             *
             * If two concurrent requests carry the same id, one loses on the primary key and its whole
             * transaction — decrement included — rolls back. The database decides, not a check-then-act.
             */
            reservationRepository.save(StockReservation.builder()
                    .id(reservationId)
                    .bookId(id)
                    .quantity(quantity)
                    .status(StockReservation.Status.ACTIVE)
                    .build());
        }

        return BookResponseDto.from(book);
    }

    @Override
    @Transactional
    public BookResponseDto release(UUID reservationId) {
        StockReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No reservation with id " + reservationId));

        Book book = getOrThrow(reservation.getBookId());

        // Releasing twice must not credit the stock twice — a compensating action gets retried at
        // least as often as the action it compensates, so it needs the same protection.
        if (reservation.getStatus() == StockReservation.Status.RELEASED) {
            log.info("Reservation {} was already released; nothing to do", reservationId);
            return BookResponseDto.from(book);
        }

        book.setStock(book.getStock() + reservation.getQuantity());
        reservation.setStatus(StockReservation.Status.RELEASED);
        reservation.setReleasedAt(Instant.now());

        log.info("Released reservation {}: {} copies of book {} back to stock",
                reservationId, reservation.getQuantity(), reservation.getBookId());

        return BookResponseDto.from(book);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        bookRepository.delete(getOrThrow(id));
    }

    private Book getOrThrow(Long id) {
        return bookRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.book(id));
    }

    /**
     * A null author id means "no author on record" and is allowed; a non-null id that matches nothing is
     * a client error, not a silently-ignored field.
     */
    private Author resolveAuthor(Long authorId) {
        if (authorId == null) {
            return null;
        }
        return authorRepository.findById(authorId)
                .orElseThrow(() -> ResourceNotFoundException.author(authorId));
    }
}
