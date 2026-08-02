package com.example.order.service;

import com.example.order.client.CatalogGateway;
import com.example.order.dto.BookSnapshot;
import com.example.order.dto.OrderItemRequestDto;
import com.example.order.dto.OrderRequestDto;
import com.example.order.dto.OrderResponseDto;
import com.example.order.dto.PageResponseDto;
import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import com.example.order.entity.OrderStatus;
import com.example.order.event.OrderEventPublisher;
import com.example.order.exception.CatalogUnavailableException;
import com.example.order.exception.OrderNotAllowedException;
import com.example.order.exception.ResourceNotFoundException;
import com.example.order.repository.OrderRepository;
import com.example.order.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CatalogGateway catalog;
    private final OrderTransactions orderTransactions;
    private final OrderEventPublisher events;

    /**
     * Places an order, as a saga.
     *
     * <p><strong>Deliberately not {@code @Transactional}.</strong> A transaction here would be the exact
     * illusion this step exists to dispel — it cannot roll back anything book-service committed, and it
     * would hold a database connection open across two network calls. Each step below commits on its
     * own, through {@link OrderTransactions}.
     *
     * <p>The order of the steps is the design:
     *
     * <ol>
     *   <li><strong>Read and validate.</strong> Free, reversible, and it rejects bad orders before
     *       anything anywhere has changed.</li>
     *   <li><strong>Write the order as PENDING, and commit.</strong> Nothing irreversible has happened
     *       yet, and from here on there is a durable record of what was meant to happen. This is the
     *       step 5b did not have, and its absence was the hole: a crash after reserving stock left the
     *       stock gone with nothing to find it by.</li>
     *   <li><strong>Reserve stock,</strong> each line under the reservation id already persisted with
     *       it. Now safe to retry, because book-service recognises a repeated id.</li>
     *   <li><strong>Mark AWAITING_PAYMENT, and commit.</strong> The saga is complete.</li>
     * </ol>
     *
     * <p>If step 3 fails, the reservations already made are released and the order becomes FAILED. If
     * the process dies anywhere in 3 or 4, the order stays PENDING and {@link OrderRecoveryJob} finishes
     * the unwinding later. Nothing is lost silently, which is the property 5b could not offer.
     */
    @Override
    public OrderResponseDto place(AuthenticatedUser customer, OrderRequestDto request) {
        Map<Long, Integer> quantities = collapseDuplicateLines(request.items());

        // --- 1. Read and validate. -------------------------------------------------------------
        Map<Long, BookSnapshot> books = new LinkedHashMap<>();
        for (Long bookId : quantities.keySet()) {
            BookSnapshot book = catalog.findById(bookId);
            if (book == null || book.id() == null) {
                throw new ResourceNotFoundException("Book not found with id " + bookId);
            }
            books.put(bookId, book);
        }
        for (var entry : quantities.entrySet()) {
            BookSnapshot book = books.get(entry.getKey());
            if (book.stock() < entry.getValue()) {
                // book-service checks again when reserving. This is a better error message and a
                // cheaper rejection, never the actual guarantee — between this read and that write
                // another customer can take the last copy.
                throw new OrderNotAllowedException(
                        "Book " + book.id() + " ('" + book.title() + "'): requested "
                                + entry.getValue() + " but only " + book.stock() + " in stock");
            }
        }

        // --- 2. Write the intent down and COMMIT it. --------------------------------------------
        Order order = orderTransactions.createPending(customer, quantities, books);
        log.info("Order {} created as PENDING with {} reservations to place",
                order.getId(), order.getItems().size());

        // --- 3. Reserve stock, under ids already on disk. ---------------------------------------
        List<OrderItem> reserved = new ArrayList<>();
        try {
            for (OrderItem item : order.getItems()) {
                catalog.purchase(item.getBookId(), item.getQuantity(), item.getReservationId());
                reserved.add(item);
            }
        } catch (RuntimeException ex) {
            log.warn("Order {} failed while reserving stock ({}); compensating {} reservation(s)",
                    order.getId(), ex.toString(), reserved.size());
            releaseAll(reserved);
            orderTransactions.markFailed(order.getId());
            throw ex;
        }

        // --- 4. The saga is complete. -----------------------------------------------------------
        Order placed = orderTransactions.markAwaitingPayment(order.getId());

        // --- 5. Tell anyone who cares, and do not wait to find out who. --------------------------
        //
        // Deliberately AFTER the commit in step 4, and deliberately not part of it. Publishing inside
        // the transaction would announce an order that a later rollback un-places, and no consumer can
        // un-read a message. Announcing something that did not happen is worse than being late.
        //
        // The cost of that ordering is the dual-write hole: the order is committed and the send can
        // still fail, leaving an order nobody was told about. See OrderEventPublisher.
        events.orderPlaced(placed);

        return OrderResponseDto.from(placed);
    }

    /**
     * Gives back stock, one reservation at a time, refusing to stop at the first problem.
     *
     * <p>The loop swallows per-item failures on purpose. A compensating action that aborts halfway
     * leaves <em>more</em> inconsistency than it started with, and the caller is already handling a
     * failure — throwing a second one from the cleanup buries the first. What cannot be released is
     * logged at ERROR with everything an operator needs, and the recovery job will try again.
     */
    private void releaseAll(List<OrderItem> items) {
        for (OrderItem item : items) {
            try {
                catalog.release(item.getReservationId());
            } catch (RuntimeException ex) {
                log.error("COMPENSATION FAILED: reservation {} for {} copies of book {} could not be "
                                + "released ({}). Stock is held with no order behind it.",
                        item.getReservationId(), item.getQuantity(), item.getBookId(), ex.toString());
            }
        }
    }

    /** Two lines for the same book are one reservation, not two — and one clearer error if it fails. */
    private Map<Long, Integer> collapseDuplicateLines(List<OrderItemRequestDto> items) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderItemRequestDto item : items) {
            quantities.merge(item.bookId(), item.quantity(), Integer::sum);
        }
        return quantities;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponseDto<OrderResponseDto> findMine(AuthenticatedUser customer, Pageable pageable) {
        return PageResponseDto.from(
                orderRepository.findByUserId(customer.id(), pageable), OrderResponseDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponseDto<OrderResponseDto> findAll(Pageable pageable) {
        return PageResponseDto.from(orderRepository.findAll(pageable), OrderResponseDto::from);
    }

    /**
     * One order, if the caller is allowed to see it.
     *
     * <p>The ownership check is here rather than in the controller because it is a rule about orders,
     * not about HTTP — and because a rule enforced in one place cannot be forgotten by a second
     * endpoint added later.
     */
    @Override
    @Transactional(readOnly = true)
    public OrderResponseDto findById(AuthenticatedUser caller, Long id) {
        Order order = orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id " + id));
        requireVisibleTo(caller, order);
        return OrderResponseDto.from(order);
    }

    /**
     * Cancels an order and returns its stock.
     *
     * <p>Not transactional, and in the same shape as {@code place}: release first, then record the
     * cancellation. If the process dies between the two, the order is still cancellable and release is
     * idempotent, so a repeat is harmless. Recording the cancellation first would risk an order that
     * looks resolved while its stock is still held — the failure that leaves no trace.
     */
    @Override
    public OrderResponseDto cancel(AuthenticatedUser caller, Long id) {
        Order order = orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id " + id));
        requireVisibleTo(caller, order);

        if (order.getStatus() == OrderStatus.SHIPPED) {
            throw new OrderNotAllowedException("Order " + id + " has already shipped");
        }
        if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.FAILED) {
            return OrderResponseDto.from(order);   // idempotent: cancelling twice is not an error
        }

        releaseAll(order.getItems());
        return OrderResponseDto.from(orderTransactions.markCancelled(id));
    }

    /**
     * Moves an order to PAID.
     *
     * <p>Deliberately tolerant of being called twice. payment-service retries this until it succeeds,
     * because once money has been taken the right move is to finish the order rather than unwind it —
     * so a second call must be a no-op, not a 409.
     *
     * <p>It is not tolerant of being called on an order that never reached AWAITING_PAYMENT. Marking a
     * PENDING order paid would mean recording money against an order whose stock was never confirmed.
     */
    @Override
    public OrderResponseDto markPaid(AuthenticatedUser caller, Long id) {
        Order order = orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id " + id));
        requireVisibleTo(caller, order);

        if (order.getStatus() == OrderStatus.PAID) {
            return OrderResponseDto.from(order);
        }
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new OrderNotAllowedException(
                    "Order " + id + " is " + order.getStatus() + " and cannot be marked paid");
        }

        return OrderResponseDto.from(orderTransactions.markPaid(id));
    }

    private void requireVisibleTo(AuthenticatedUser caller, Order order) {
        boolean isOwner = order.getUserId().equals(caller.id());
        boolean isAdmin = "ADMIN".equals(caller.role());
        if (!isOwner && !isAdmin) {
            // 404, not 403. Answering "forbidden" confirms the order exists, which lets anyone probe
            // for valid ids and learn how many orders the shop has taken.
            throw new ResourceNotFoundException("Order not found with id " + order.getId());
        }
    }
}
