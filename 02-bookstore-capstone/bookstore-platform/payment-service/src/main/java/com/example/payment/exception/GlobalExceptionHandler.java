package com.example.payment.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.AuthenticationException;
import com.example.payment.exception.PaymentNotAllowedException;
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

    /*
     * DuplicateResourceException and InsufficientStockException are gone from here — the second time
     * the split has caught a handler for a concern the service does not own. Stock belongs to the
     * catalog; order-service learns about it only as a 409 from order-service, translated by
     * BookClientErrorConfig into OrderNotAllowedException. Uniqueness is not an order concept at all.
     */

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

    @ExceptionHandler(PaymentNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleNotAllowed(PaymentNotAllowedException ex,
                                                          HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(409, "Conflict", ex.getMessage(), request.getRequestURI()));
    }

    /**
     * order-service could not be reached, or refused this service's credentials.
     *
     * <p>503, not 500. The distinction is not pedantry: 503 tells a client (and a load balancer, and a
     * retry policy) that the request was fine and the system is temporarily unable to serve it, which
     * is exactly true. A 500 says "you broke something", and gets someone debugging order-service when
     * the fault is one hop away.
     */
    @ExceptionHandler(OrderServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleCatalogUnavailable(OrderServiceUnavailableException ex,
                                                                  HttpServletRequest request) {
        log.error("Downstream failure on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(503, "Service Unavailable",
                        "The order service is temporarily unavailable. Please try again.",
                        request.getRequestURI()));
    }

    /**
     * order-service could not be reached at all — connection refused, DNS failure, or a timeout.
     *
     * <p>Separate from {@link OrderServiceUnavailableException} because it arrives by a different route, and
     * that difference cost a debugging session worth writing down: **an {@code ErrorDecoder} only sees
     * HTTP responses.** When there is no response — the process is down, the port is closed, the read
     * timed out — Feign throws before any decoder runs, so the carefully-written mapping never fires
     * and the client gets a 500.
     *
     * <p>Which is the worst possible answer here. "Internal Server Error" says order-service is broken;
     * it is fine, and the catalog is down. 503 says the request was valid and the system cannot serve
     * it right now, which is both true and actionable — a client may retry, a load balancer may route
     * elsewhere, and an on-call engineer looks at the right service.
     *
     * <p>Returning a status is still only damage control: the request failed. Step 5c stops it reaching
     * this point on every attempt, by failing fast once the catalog is known to be down.
     */
    @ExceptionHandler(feign.FeignException.class)
    public ResponseEntity<ErrorResponse> handleFeignTransport(feign.FeignException ex,
                                                              HttpServletRequest request) {
        log.error("Could not reach order-service on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(503, "Service Unavailable",
                        "The order service is temporarily unavailable. Please try again.",
                        request.getRequestURI()));
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
