package com.example.bookstore.exception;

/** Thrown when a purchase asks for more copies than the catalog holds. Mapped to HTTP 409. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long bookId, int requested, int available) {
        super("Book " + bookId + ": requested " + requested + " but only " + available + " in stock");
    }
}
