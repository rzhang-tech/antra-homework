package com.example.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The order this pays for — and the idempotency key.
     *
     * <p>UNIQUE in the schema, which is the entire duplicate-charge defence. order-service and
     * payment-service have no shared transaction, so a retried payment request is always possible: the
     * response can be lost, the client can double-submit, a proxy can replay. Every one of those ends
     * at the same constraint, and the database decides.
     *
     * <p>Deliberately not a caller-supplied idempotency key like the stock reservation's. The business
     * rule here is stronger — "one payment per order" is true regardless of who asks or how often — so
     * the natural key is better than a synthetic one. A caller that forgot to send a key could still
     * double-charge; a caller cannot forget the order id.
     *
     * <p>Not a foreign key: orders live in another service's database (D5).
     */
    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    /** From the token, so a payment can be attributed without asking user-service. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * The amount charged, as order-service reported it at the time.
     *
     * <p>Never taken from the request. A client that could name the amount could pay one cent for a
     * hundred-pound order.
     */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    /**
     * Whether order-service has been told about this payment yet.
     *
     * <p>The money moving and the order changing state are two writes in two databases. This flag is
     * what lets a recovery process find payments whose second write never landed — the same
     * write-it-down-first principle as the order saga, applied to the other half of the conversation.
     */
    @Column(name = "order_notified", nullable = false)
    @Builder.Default
    private boolean orderNotified = false;
}
