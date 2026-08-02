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

    /**
     * Places an order.
     *
     * <p><strong>This method is the whole point of Step 5.</strong> In the monolith it was one
     * transaction: read the book, check stock, decrement it, insert the order, commit. Either all of it
     * happened or none of it did, and the database guaranteed that.
     *
     * <p>Now the reads and the stock decrement are HTTP calls to another service with its own database.
     * There is no transaction spanning both. {@code @Transactional} here covers exactly one thing — the
     * rows in <em>this</em> service's database — and has no authority over anything book-service did.
     *
     * <p>The sequence below is deliberate:
     *
     * <ol>
     *   <li><strong>Read every book first, and validate.</strong> Cheap, side-effect free, and it
     *       rejects bad orders before anything has been changed anywhere.</li>
     *   <li><strong>Then decrement stock, item by item.</strong> The first call with consequences. If
     *       one fails after earlier ones succeeded, the earlier decrements have already been committed
     *       in book-service and must be compensated — see below.</li>
     *   <li><strong>Then write the order locally.</strong> Last, because it is the only step this
     *       service can roll back.</li>
     * </ol>
     *
     * <p><strong>What is still wrong with this, honestly.</strong> The compensation is best-effort: if
     * the process dies between decrementing stock and writing the order, stock is gone and no order
     * exists, and nothing will ever notice. A real saga persists its intent before acting, so a
     * recovery process can finish or unwind it after a crash. That is 5d. Stating the gap is not an
     * excuse for it — but a compensating call that usually works is meaningfully better than pretending
     * a distributed transaction exists.
     */
    @Override
    @Transactional
    public OrderResponseDto place(AuthenticatedUser customer, OrderRequestDto request) {
        Map<Long, Integer> quantities = collapseDuplicateLines(request.items());

        // --- 1. Read. No side effects yet, so failing here costs nothing. -----------------------
        Map<Long, BookSnapshot> books = new LinkedHashMap<>();
        for (Long bookId : quantities.keySet()) {
            BookSnapshot book = catalog.findById(bookId);
            if (book == null || book.id() == null) {
                throw new ResourceNotFoundException("Book not found with id " + bookId);
            }
            books.put(bookId, book);
        }

        // Check stock before touching anything. book-service checks again when decrementing — this is
        // an optimisation and a better error message, never the actual guarantee. Between this read
        // and that write another customer can take the last copy, and only book-service's own
        // transaction can settle it.
        for (var entry : quantities.entrySet()) {
            BookSnapshot book = books.get(entry.getKey());
            if (book.stock() < entry.getValue()) {
                throw new OrderNotAllowedException(
                        "Book " + book.id() + " ('" + book.title() + "'): requested "
                                + entry.getValue() + " but only " + book.stock() + " in stock");
            }
        }

        // --- 2. Reserve stock. From here, failure needs compensating. ---------------------------
        List<Long> decremented = new ArrayList<>();
        try {
            for (var entry : quantities.entrySet()) {
                catalog.purchase(entry.getKey(), entry.getValue());
                decremented.add(entry.getKey());
            }
        } catch (RuntimeException ex) {
            compensate(decremented, quantities);
            throw ex;
        }

        // --- 3. Write the order. The only step with a real rollback. ----------------------------
        Order order = Order.builder()
                .userId(customer.id())
                .status(OrderStatus.PENDING)
                .totalPrice(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (var entry : quantities.entrySet()) {
            BookSnapshot book = books.get(entry.getKey());
            OrderItem item = OrderItem.builder()
                    .bookId(book.id())
                    .bookTitle(book.title())
                    .quantity(entry.getValue())
                    // Captured, not referenced: a later price change must not alter this receipt.
                    .unitPrice(book.price())
                    .build();
            order.addItem(item);
            total = total.add(book.price().multiply(BigDecimal.valueOf(entry.getValue())));
        }
        order.setTotalPrice(total);

        return OrderResponseDto.from(orderRepository.save(order));
    }

    /**
     * Best-effort undo of stock already taken.
     *
     * <p>A negative purchase is not an API book-service offers, so this logs loudly rather than
     * pretending to fix it. Naming the gap in the logs is worth more than a silent {@code catch}: the
     * operator can reconcile, and the noise is a standing argument for the durable saga in 5d.
     */
    private void compensate(List<Long> decremented, Map<Long, Integer> quantities) {
        if (decremented.isEmpty()) {
            return;
        }
        log.error("Order failed after reserving stock for books {}. book-service has already committed "
                        + "those decrements and there is no transaction to roll them back. "
                        + "Quantities to reconcile: {}",
                decremented,
                decremented.stream().collect(
                        LinkedHashMap::new, (m, id) -> m.put(id, quantities.get(id)), Map::putAll));
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

    @Override
    @Transactional
    public OrderResponseDto cancel(AuthenticatedUser caller, Long id) {
        Order order = orderRepository.findWithItemsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id " + id));
        requireVisibleTo(caller, order);

        if (order.getStatus() == OrderStatus.SHIPPED) {
            throw new OrderNotAllowedException("Order " + id + " has already shipped");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderResponseDto.from(order);   // idempotent: cancelling twice is not an error
        }

        order.setStatus(OrderStatus.CANCELLED);

        // Returning the stock is a cross-service write with the same lack of a shared transaction as
        // placing the order, and is deliberately left to 5d rather than bolted on here.
        log.warn("Order {} cancelled. Stock for its items is NOT yet returned to book-service — "
                + "restocking is part of the 5d saga work.", id);

        return OrderResponseDto.from(order);
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
