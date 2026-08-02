package com.example.book.controller;

import com.example.book.dto.BookRequestDto;
import com.example.book.dto.BookResponseDto;
import com.example.book.dto.BookViewDto;
import com.example.book.dto.PageResponseDto;
import com.example.book.dto.PurchaseRequestDto;
import com.example.book.security.AuthenticatedUser;
import com.example.book.service.BookService;
import com.example.book.service.BrowsingHistoryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * HTTP entry point for the catalog.
 *
 * <p>The controller's whole job is translation: HTTP in, HTTP out. It holds no business rules — it does
 * not decide what a duplicate ISBN means or when a book is missing. Those live in the service, which is
 * why the service can be reused unchanged when this becomes book-service in Step 5.
 *
 * <p>Every endpoint here is public in Step 1. Step 3 makes the reads PUBLIC and the writes ADMIN.
 */
@RestController
@RequestMapping("/api/books")
@RequiredArgsConstructor
public class BookController {

    private final BookService bookService;
    private final BrowsingHistoryService browsingHistory;

    /** List or keyword-search books. Paging: {@code ?page=0&size=20&sort=title,asc}. */
    @GetMapping
    public PageResponseDto<BookResponseDto> list(
            @RequestParam(required = false) String keyword,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return bookService.findAll(keyword, pageable);
    }

    /**
     * Public, and it records history for whoever is logged in.
     *
     * <p>The two halves are deliberately independent. An anonymous visitor gets the book and no history
     * entry; a logged-in one gets the same book at the same speed and an entry appears on another
     * thread shortly afterwards. Nothing about the response changes, and nothing about the response
     * waits — which is why {@code recordView} is called after the read has produced its answer rather
     * than around it.
     *
     * <p>The identity comes from the token, never from a parameter. There is no endpoint anywhere that
     * lets a caller name whose history to write to.
     */
    @GetMapping("/{id}")
    public BookResponseDto get(@PathVariable Long id,
                               @AuthenticationPrincipal AuthenticatedUser viewer) {
        BookResponseDto book = bookService.findById(id);
        browsingHistory.recordView(viewer, id, book.title());
        return book;
    }

    /**
     * The caller's recently viewed books, newest first. USER or ADMIN.
     *
     * <p>{@code /me/} rather than {@code /{userId}/} on purpose: an endpoint that took a user id in the
     * path would need a check that the caller may read it, and the check nobody writes is the check
     * nobody gets wrong. Two path segments also keep it clear of {@code GET /api/books/{id}}, which is
     * public — {@code /api/books/*} does not match {@code /api/books/me/history}, so the gateway's
     * public-route list does not accidentally open it.
     */
    @GetMapping("/me/history")
    public List<BookViewDto> myHistory(@AuthenticationPrincipal AuthenticatedUser viewer,
                                       @RequestParam(defaultValue = "20") int limit) {
        return browsingHistory.recentViews(viewer, Math.clamp(limit, 1, 100));
    }

    /** 201 Created with a {@code Location} header pointing at the new resource. */
    @PostMapping
    public ResponseEntity<BookResponseDto> create(@Valid @RequestBody BookRequestDto request) {
        BookResponseDto created = bookService.create(request);
        return ResponseEntity.created(URI.create("/api/books/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public BookResponseDto update(@PathVariable Long id, @Valid @RequestBody BookRequestDto request) {
        return bookService.update(id, request);
    }

    /**
     * Sell copies of a book. Returns 409 if stock is insufficient, or if a concurrent purchase won the
     * race for the same rows (optimistic-lock failure) — in both cases the client may re-read and retry.
     */
    @PostMapping("/{id}/purchase")
    public BookResponseDto purchase(@PathVariable Long id,
                                    @Valid @RequestBody PurchaseRequestDto request) {
        return bookService.purchase(id, request.quantity(), request.reservationId());
    }

    /**
     * Returns reserved stock to the shelf.
     *
     * <p>Requires the same roles as purchasing rather than ADMIN: the caller undoing a reservation is
     * the service that made it, acting for the customer whose order failed. Restricting it to staff
     * would mean a failed order could only be compensated by a human.
     */
    @PostMapping("/reservations/{reservationId}/release")
    public BookResponseDto release(@PathVariable UUID reservationId) {
        return bookService.release(reservationId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        bookService.delete(id);
    }
}
