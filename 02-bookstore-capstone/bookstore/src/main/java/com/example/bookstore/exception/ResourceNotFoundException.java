package com.example.bookstore.exception;

/** Thrown when a requested resource does not exist. Mapped to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException book(Long id) {
        return new ResourceNotFoundException("Book not found with id " + id);
    }

    public static ResourceNotFoundException author(Long id) {
        return new ResourceNotFoundException("Author not found with id " + id);
    }
}
