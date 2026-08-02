package com.example.order.exception;

/** The order cannot be placed or changed as asked. Mapped to HTTP 409. */
public class OrderNotAllowedException extends RuntimeException {
    public OrderNotAllowedException(String message) {
        super(message);
    }
}
