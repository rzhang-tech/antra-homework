package com.example.payment.entity;

/**
 * The outcome of a payment attempt.
 *
 * <p>Only two values, and no PENDING among them. A payment row is written after the (simulated)
 * charge has returned an answer, so it always records something that happened. A PENDING payment would
 * be a claim about the outside world that this service cannot verify — the kind of state that turns
 * into a reconciliation problem.
 */
public enum PaymentStatus {
    SUCCESS,
    FAILED
}
