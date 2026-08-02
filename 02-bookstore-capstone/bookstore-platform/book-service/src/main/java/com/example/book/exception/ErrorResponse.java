package com.example.book.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * The single error shape every failing request returns, so clients parse one format instead of guessing
 * between a Spring default body, a stack trace, and a hand-written string.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        /** Field-level validation failures: field name -> reason. Absent unless status is 400. */
        Map<String, String> fieldErrors
) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse validation(String path, Map<String, String> fieldErrors) {
        return new ErrorResponse(Instant.now(), 400, "Bad Request",
                "Validation failed", path, fieldErrors);
    }
}
