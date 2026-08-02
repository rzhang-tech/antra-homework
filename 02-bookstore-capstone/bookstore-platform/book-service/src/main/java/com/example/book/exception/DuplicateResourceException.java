package com.example.book.exception;

/** Thrown when a write would violate a uniqueness rule (e.g. a duplicate ISBN). Mapped to HTTP 409. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
