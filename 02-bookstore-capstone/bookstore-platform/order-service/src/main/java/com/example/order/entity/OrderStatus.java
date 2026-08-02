package com.example.order.entity;

/** Where an order is in its life. Payment moves PENDING to PAID in 5d. */
public enum OrderStatus {
    /** Created and stock reserved, but not yet paid for. */
    PENDING,
    PAID,
    CANCELLED,
    SHIPPED
}
