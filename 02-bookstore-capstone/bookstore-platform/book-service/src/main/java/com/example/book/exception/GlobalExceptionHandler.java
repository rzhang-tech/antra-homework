package com.example.book.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns exceptions into the {@link ErrorResponse} envelope, in one place.
 *
 * <p>Without this, every controller method would need its own try/catch, and an unhandled exception
 * would leak a stack trace and a 500 to the client. With it, the service layer throws a meaningful
 * domain exception and the HTTP status is decided here — the layers stay separated.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(404, "Not Found", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateResourceException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientStock(InsufficientStockException ex,
                                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }

    /**
     * A concurrent transaction changed the row first, so this one's
     * {@code UPDATE ... WHERE id = ? AND version = ?} matched nothing and was rolled back.
     *
     * <p>409 rather than 500: nothing is broken, the client simply lost a race. Re-reading the resource
     * and retrying is the correct response, which is exactly what 409 tells a client to consider.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                              HttpServletRequest request) {
        log.warn("Optimistic lock conflict on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "Conflict",
                        "This record was modified by another request. Re-read it and try again.",
                        request.getRequestURI()));
    }

    /**
     * Backstop for a database constraint the application checked but lost a race on.
     *
     * <p>{@code BookServiceImpl.create} tests {@code existsByIsbn} and then saves — a check-then-act
     * with a gap. Two concurrent requests carrying the same ISBN can both pass the check, and the
     * unique constraint rejects the second. Without this handler that surfaced as a 500, because the
     * exception is Spring's, not one of ours.
     *
     * <p>The application check is still worth keeping: it produces a precise message in the common case.
     * This handler covers the narrow window the check cannot close — the database has the final say.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.warn("Constraint violation on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "Conflict",
                        "The request conflicts with existing data", request.getRequestURI()));
    }

    /**
     * A failed login.
     *
     * <p>Deliberately one message for both "no such user" and "wrong password". Distinguishing them
     * turns the login endpoint into a username oracle: an attacker learns which accounts exist and can
     * then concentrate password guessing on the real ones.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        log.debug("Failed authentication on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of(401, "Unauthorized",
                        "Invalid username or password", request.getRequestURI()));
    }

    /** Raised when an {@code @Valid @RequestBody} fails Bean Validation. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ErrorResponse.validation(request.getRequestURI(), fieldErrors));
    }

    /**
     * Last resort. Logs the full stack trace server-side but returns a generic message, so internal
     * details never reach the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(500, "Internal Server Error",
                        "An unexpected error occurred", request.getRequestURI()));
    }
}
