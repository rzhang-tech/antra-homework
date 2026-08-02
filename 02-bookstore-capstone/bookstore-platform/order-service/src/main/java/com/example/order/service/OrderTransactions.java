package com.example.order.service;

import com.example.order.dto.BookSnapshot;
import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import com.example.order.entity.OrderStatus;
import com.example.order.exception.ResourceNotFoundException;
import com.example.order.repository.OrderRepository;
import com.example.order.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The saga's individual, separately-committed steps.
 *
 * <p><strong>Why this is its own bean.</strong> A saga step must be durable the instant it completes —
 * the whole point is that the record survives the process dying immediately afterwards. That requires
 * each step to be its own transaction, committed before the next begins.
 *
 * <p>Calling a {@code @Transactional} method from another method of the same class does not do that.
 * Spring AOP is proxy-based, so a self-invocation bypasses the proxy entirely and the annotation has no
 * effect — the same limitation documented on {@code LoggingAspect} back in Step 1, resurfacing where
 * getting it wrong would be invisible and expensive. Putting the steps in a separate bean means every
 * call goes through the proxy, and every step really does commit.
 *
 * <p>So the orchestration in {@code OrderServiceImpl.place} is deliberately <em>not</em> transactional.
 * A transaction spanning the whole method would be exactly the illusion this step exists to dispel:
 * it cannot roll back anything book-service did, and holding a database connection open across two
 * network calls is its own problem.
 */
@Component
@RequiredArgsConstructor
public class OrderTransactions {

    private final OrderRepository orderRepository;

    /**
     * Step 1: write the intent down, and commit it.
     *
     * <p>Everything after this point is recoverable, because there is a row saying what was meant to
     * happen. Everything before it was free to fail.
     *
     * <p>Reservation ids are generated here rather than at the call site so they are persisted with the
     * order in the same transaction. An id created later and lost in a crash would be an unreleasable
     * reservation — stock held by nothing, forever.
     */
    @Transactional
    public Order createPending(AuthenticatedUser customer,
                               Map<Long, Integer> quantities,
                               Map<Long, BookSnapshot> books) {
        Order order = Order.builder()
                .userId(customer.id())
                .status(OrderStatus.PENDING)
                .totalPrice(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (var entry : quantities.entrySet()) {
            BookSnapshot book = books.get(entry.getKey());
            order.addItem(OrderItem.builder()
                    .bookId(book.id())
                    .bookTitle(book.title())
                    .quantity(entry.getValue())
                    .unitPrice(book.price())
                    .reservationId(UUID.randomUUID())
                    .build());
            total = total.add(book.price().multiply(BigDecimal.valueOf(entry.getValue())));
        }
        order.setTotalPrice(total);

        return orderRepository.save(order);
    }

    /** Step 3: every reservation confirmed, so the order is real. */
    @Transactional
    public Order markAwaitingPayment(Long orderId) {
        Order order = load(orderId);
        order.transitionTo(OrderStatus.AWAITING_PAYMENT);
        return order;
    }

    /**
     * The saga could not complete and its reservations have been released.
     *
     * <p>Marked FAILED rather than deleted. A customer whose order failed should be able to see that it
     * failed rather than find nothing, and an operator reconciling stock needs the trail.
     */
    @Transactional
    public Order markFailed(Long orderId) {
        Order order = load(orderId);
        order.transitionTo(OrderStatus.FAILED);
        return order;
    }

    @Transactional
    public Order markCancelled(Long orderId) {
        Order order = load(orderId);
        order.transitionTo(OrderStatus.CANCELLED);
        return order;
    }

    @Transactional
    public Order markPaid(Long orderId) {
        Order order = load(orderId);
        order.transitionTo(OrderStatus.PAID);
        return order;
    }

    private Order load(Long orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id " + orderId));
    }
}
