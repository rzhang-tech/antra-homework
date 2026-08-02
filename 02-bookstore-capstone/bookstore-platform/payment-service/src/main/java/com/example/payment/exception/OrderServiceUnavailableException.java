package com.example.payment.exception;

/**
 * order-service could not be reached, or did not answer in time. Mapped to HTTP 503.
 *
 * <p>An exception type the monolith had no use for: a method call cannot be unreachable. It exists
 * because 503 says something true and useful — "this is our problem, not yours; try again" — where a
 * 500 would blame the request and a 200 would lie.
 */
public class OrderServiceUnavailableException extends RuntimeException {
    public OrderServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
