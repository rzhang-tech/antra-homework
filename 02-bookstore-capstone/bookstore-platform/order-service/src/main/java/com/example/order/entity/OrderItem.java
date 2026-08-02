package com.example.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "order_item")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** A book in book-service's database, referenced by id only. No foreign key is possible. */
    @Column(name = "book_id", nullable = false)
    private Long bookId;

    /**
     * The title as it was when the order was placed.
     *
     * <p>Denormalised on purpose. Rendering an order should not require a call to book-service — that
     * would make order history unavailable whenever the catalog is down, and would show today's title
     * for a book bought last year. An order is a record of what happened, not a live view.
     */
    @Column(name = "book_title", nullable = false)
    private String bookTitle;

    @Column(nullable = false)
    private Integer quantity;

    /**
     * The price paid, captured at order time.
     *
     * <p>Not looked up later: a price change must never alter what a past customer was charged. The
     * same reasoning as the title, but with money, where getting it wrong is a real problem.
     */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;
}
