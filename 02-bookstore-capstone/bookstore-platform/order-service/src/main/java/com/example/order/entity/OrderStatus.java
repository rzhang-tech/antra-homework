package com.example.order.entity;

/**
 * Where an order is in its life — and, for the first two, where its <em>saga</em> is.
 *
 * <p>{@link #PENDING} is not "a finished order awaiting payment". It means the order has been written
 * down but the work in other services has not been confirmed. Distinguishing that from
 * {@link #AWAITING_PAYMENT} is what lets a recovery process tell an interrupted order from a healthy
 * one — without the distinction, a crashed saga is indistinguishable from a customer who has not paid
 * yet.
 */
public enum OrderStatus {

    /** Written down; stock reservation not yet confirmed. An in-flight saga. */
    PENDING,

    /** Every reservation succeeded. The order is real and the customer owes money. */
    AWAITING_PAYMENT,

    PAID,

    /** Cancelled by a customer or an admin; reservations released. */
    CANCELLED,

    /** The saga could not complete and was unwound. Kept rather than deleted — a customer whose order
     *  failed deserves to see that it failed, and an operator needs the trail. */
    FAILED,

    SHIPPED
}
