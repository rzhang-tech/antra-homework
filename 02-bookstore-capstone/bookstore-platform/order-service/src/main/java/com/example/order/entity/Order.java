package com.example.order.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A customer's order.
 *
 * <p>Mapped to {@code orders}: ORDER is a reserved word in SQL.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The customer, as a bare id read from the token's {@code uid} claim.
     *
     * <p>Not a foreign key, and it cannot be one: the users table lives in another service's database,
     * which this service has no credentials for. The database can no longer enforce that this id refers
     * to a real person — that guarantee now comes from the signature on the token that supplied it.
     * This is Database-per-Service in one field (D5).
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "total_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalPrice;

    /**
     * Cascade ALL and orphan removal: order items have no life of their own. An OrderItem outside an
     * Order is meaningless, so the two are one aggregate and the parent owns the children's lifecycle.
     * Contrast with Book -> Author, where an author very much exists without any particular book.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @jakarta.persistence.Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Keeps both sides of the relation consistent — setting only one is the classic JPA bug. */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }
}
