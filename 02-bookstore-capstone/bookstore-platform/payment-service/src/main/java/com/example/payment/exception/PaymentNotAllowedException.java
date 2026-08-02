package com.example.payment.exception;

/** The payment cannot be made as asked — wrong order state, or declined. Mapped to HTTP 409. */
public class PaymentNotAllowedException extends RuntimeException {
    public PaymentNotAllowedException(String message) {
        super(message);
    }
}
