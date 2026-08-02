package com.example.order.service;

import com.example.order.client.CatalogGateway;
import com.example.order.dto.BookSnapshot;
import com.example.order.dto.OrderItemRequestDto;
import com.example.order.dto.OrderRequestDto;
import com.example.order.dto.OrderResponseDto;
import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import com.example.order.entity.OrderStatus;
import com.example.order.exception.CatalogUnavailableException;
import com.example.order.exception.OrderNotAllowedException;
import com.example.order.exception.ResourceNotFoundException;
import com.example.order.repository.OrderRepository;
import com.example.order.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for placing and reading orders, with book-service mocked.
 *
 * <p>Mocking the gateway is the right level here: these tests are about order-service's rules —
 * what it validates, what it captures, who may see what. Whether the HTTP call itself behaves is a
 * different question, answered by the WireMock-backed tests in 5c.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceImpl")
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private CatalogGateway catalog;
    @Mock private OrderTransactions orderTransactions;

    @InjectMocks private OrderServiceImpl orderService;

    private static final AuthenticatedUser CUSTOMER = new AuthenticatedUser(7L, "buyer", "USER");
    private static final AuthenticatedUser OTHER = new AuthenticatedUser(8L, "nosy", "USER");
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin", "ADMIN");

    private static BookSnapshot book(long id, String title, String price, int stock) {
        return new BookSnapshot(id, title, new BigDecimal(price), stock);
    }

    private static OrderRequestDto order(long bookId, int quantity) {
        return new OrderRequestDto(List.of(new OrderItemRequestDto(bookId, quantity)));
    }

    /**
     * Stands in for the committed step-2 write.
     *
     * <p>Building the order here rather than in the service is the point of {@link OrderTransactions}
     * being a separate bean: the saga's steps are separately committed, so the orchestration under test
     * is the sequencing and the compensation, not the persistence.
     */
    private Order expectPendingOrder(Map<Long, Integer> lines, Map<Long, BookSnapshot> books) {
        Order order = Order.builder()
                .id(1L).userId(CUSTOMER.id()).status(OrderStatus.PENDING)
                .totalPrice(BigDecimal.ZERO).version(0L)
                .build();
        lines.forEach((bookId, qty) -> {
            BookSnapshot book = books.get(bookId);
            order.addItem(OrderItem.builder()
                    .bookId(bookId).bookTitle(book.title()).quantity(qty)
                    .unitPrice(book.price()).reservationId(UUID.randomUUID())
                    .build());
        });
        when(orderTransactions.createPending(any(), any(), any())).thenReturn(order);
        // lenient: the failure-path tests never reach this step, and strict stubbing would flag the
        // unused stub as a mistake rather than as the shared fixture it is.
        lenient().when(orderTransactions.markAwaitingPayment(1L)).thenAnswer(inv -> {
            order.transitionTo(OrderStatus.AWAITING_PAYMENT);
            return order;
        });
        return order;
    }

    private Order expectPendingOrder(long bookId, int quantity, BookSnapshot book) {
        return expectPendingOrder(Map.of(bookId, quantity), Map.of(bookId, book));
    }

    @Nested
    @DisplayName("place (saga)")
    class Place {

        private static final BookSnapshot CLEAN_CODE =
                new BookSnapshot(1L, "Clean Code", new BigDecimal("42.50"), 10);

        @Test
        @DisplayName("captures the price at order time rather than referencing it")
        void capturesPrice() {
            when(catalog.findById(1L)).thenReturn(CLEAN_CODE);
            expectPendingOrder(1L, 2, CLEAN_CODE);

            OrderResponseDto result = orderService.place(CUSTOMER, order(1L, 2));

            assertThat(result.items()).hasSize(1);
            assertThat(result.items().getFirst().unitPrice()).isEqualByComparingTo("42.50");
            assertThat(result.items().getFirst().bookTitle()).isEqualTo("Clean Code");
        }

        @Test
        @DisplayName("writes the order BEFORE reserving stock — the step that makes recovery possible")
        void persistsIntentBeforeActing() {
            when(catalog.findById(1L)).thenReturn(CLEAN_CODE);
            expectPendingOrder(1L, 1, CLEAN_CODE);

            orderService.place(CUSTOMER, order(1L, 1));

            // Order matters more than the calls themselves: a crash after the reservation but before
            // the order existed would leave stock gone with nothing to find it by. That was 5b's hole.
            InOrder sequence = inOrder(orderTransactions, catalog);
            sequence.verify(orderTransactions).createPending(any(), any(), any());
            sequence.verify(catalog).purchase(eq(1L), eq(1), any(UUID.class));
            sequence.verify(orderTransactions).markAwaitingPayment(1L);
        }

        @Test
        @DisplayName("reserves under the id persisted with the order, so a retry is recognisable")
        void reservesUnderThePersistedId() {
            when(catalog.findById(1L)).thenReturn(CLEAN_CODE);
            Order pending = expectPendingOrder(1L, 3, CLEAN_CODE);
            UUID persistedId = pending.getItems().getFirst().getReservationId();

            orderService.place(CUSTOMER, order(1L, 3));

            verify(catalog).purchase(1L, 3, persistedId);
        }

        @Test
        @DisplayName("the order ends AWAITING_PAYMENT once every reservation succeeds")
        void completesTheSaga() {
            when(catalog.findById(1L)).thenReturn(CLEAN_CODE);
            expectPendingOrder(1L, 1, CLEAN_CODE);

            assertThat(orderService.place(CUSTOMER, order(1L, 1)).status())
                    .isEqualTo(OrderStatus.AWAITING_PAYMENT);
        }

        @Test
        @DisplayName("a failed reservation releases what was already taken and marks the order FAILED")
        void compensatesOnFailure() {
            BookSnapshot other = new BookSnapshot(2L, "Effective Java", new BigDecimal("49.99"), 10);
            when(catalog.findById(1L)).thenReturn(CLEAN_CODE);
            when(catalog.findById(2L)).thenReturn(other);

            // A LinkedHashMap built in order, not Map.of — the test asserts which reservation is
            // released, so the iteration order has to be the one the service will actually see.
            var lines = new java.util.LinkedHashMap<Long, Integer>();
            lines.put(1L, 1);
            lines.put(2L, 1);
            Order pending = expectPendingOrder(lines, Map.of(1L, CLEAN_CODE, 2L, other));
            UUID firstId = pending.getItems().getFirst().getReservationId();

            // Both calls are stubbed, not just the failing one: Mockito's strict stubbing treats an
            // unstubbed call on a mock that has other stubs for the same method as a test-authoring
            // mistake, which it usually is.
            when(catalog.purchase(eq(1L), anyInt(), any(UUID.class))).thenReturn(CLEAN_CODE);
            when(catalog.purchase(eq(2L), anyInt(), any(UUID.class)))
                    .thenThrow(new CatalogUnavailableException("catalog down", null));

            assertThatThrownBy(() -> orderService.place(CUSTOMER, new OrderRequestDto(List.of(
                    new OrderItemRequestDto(1L, 1),
                    new OrderItemRequestDto(2L, 1)))))
                    .isInstanceOf(CatalogUnavailableException.class);

            // Exactly the reservation that succeeded is given back — no guessing, because the id was
            // persisted with the order before the call.
            verify(catalog).release(firstId);
            verify(orderTransactions).markFailed(1L);
            verify(orderTransactions, never()).markAwaitingPayment(anyLong());
        }

        @Test
        @DisplayName("rejects an unknown book before writing anything at all")
        void rejectsUnknownBook() {
            when(catalog.findById(99L)).thenReturn(null);

            assertThatThrownBy(() -> orderService.place(CUSTOMER, order(99L, 1)))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(orderTransactions, never()).createPending(any(), any(), any());
            verify(catalog, never()).purchase(anyLong(), anyInt(), any());
        }

        @Test
        @DisplayName("refuses to oversell before writing anything at all")
        void refusesOverselling() {
            when(catalog.findById(1L))
                    .thenReturn(new BookSnapshot(1L, "Clean Code", new BigDecimal("42.50"), 3));

            assertThatThrownBy(() -> orderService.place(CUSTOMER, order(1L, 4)))
                    .isInstanceOf(OrderNotAllowedException.class)
                    .hasMessageContaining("only 3");

            // Validation is free and reversible, so it happens before the first durable write.
            verify(orderTransactions, never()).createPending(any(), any(), any());
            verify(catalog, never()).purchase(anyLong(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("visibility")
    class Visibility {

        private Order existingOrder() {
            Order order = Order.builder()
                    .id(1L).userId(7L).status(OrderStatus.PENDING)
                    .totalPrice(new BigDecimal("42.50")).version(0L)
                    .build();
            order.addItem(OrderItem.builder()
                    .bookId(1L).bookTitle("Clean Code").quantity(1)
                    .unitPrice(new BigDecimal("42.50")).build());
            return order;
        }

        @Test
        @DisplayName("the owner can read their own order")
        void ownerCanRead() {
            when(orderRepository.findWithItemsById(1L)).thenReturn(Optional.of(existingOrder()));

            assertThat(orderService.findById(CUSTOMER, 1L).id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("an admin can read anyone's order")
        void adminCanRead() {
            when(orderRepository.findWithItemsById(1L)).thenReturn(Optional.of(existingOrder()));

            assertThat(orderService.findById(ADMIN, 1L).id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("another customer gets 404, not 403 — a 403 would confirm the order exists")
        void strangerGetsNotFound() {
            when(orderRepository.findWithItemsById(1L)).thenReturn(Optional.of(existingOrder()));

            assertThatThrownBy(() -> orderService.findById(OTHER, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("cancelling someone else's order is refused the same way")
        void strangerCannotCancel() {
            when(orderRepository.findWithItemsById(1L)).thenReturn(Optional.of(existingOrder()));

            assertThatThrownBy(() -> orderService.cancel(OTHER, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        private Order orderWithStatus(OrderStatus status) {
            Order order = Order.builder()
                    .id(1L).userId(7L).status(status)
                    .totalPrice(new BigDecimal("42.50")).version(0L)
                    .build();
            order.addItem(OrderItem.builder()
                    .bookId(1L).bookTitle("Clean Code").quantity(1)
                    .unitPrice(new BigDecimal("42.50")).reservationId(UUID.randomUUID())
                    .build());
            return order;
        }

        @Test
        @DisplayName("a pending order becomes CANCELLED, and its stock goes back first")
        void cancelsPending() {
            Order order = orderWithStatus(OrderStatus.PENDING);
            UUID reservationId = order.getItems().getFirst().getReservationId();
            when(orderRepository.findWithItemsById(1L)).thenReturn(Optional.of(order));
            when(orderTransactions.markCancelled(1L)).thenAnswer(inv -> {
                order.transitionTo(OrderStatus.CANCELLED);
                return order;
            });

            assertThat(orderService.cancel(CUSTOMER, 1L).status()).isEqualTo(OrderStatus.CANCELLED);

            // Released before the status is recorded: a crash in between leaves the order cancellable
            // and release idempotent, where the other order would leave stock held by an order that
            // looks resolved.
            InOrder sequence = inOrder(catalog, orderTransactions);
            sequence.verify(catalog).release(reservationId);
            sequence.verify(orderTransactions).markCancelled(1L);
        }

        @Test
        @DisplayName("cancelling twice is not an error, and does not release the stock twice")
        void cancelIsIdempotent() {
            when(orderRepository.findWithItemsById(1L))
                    .thenReturn(Optional.of(orderWithStatus(OrderStatus.CANCELLED)));

            assertThat(orderService.cancel(CUSTOMER, 1L).status()).isEqualTo(OrderStatus.CANCELLED);

            // book-service would ignore a second release anyway; not making the call is better still.
            verify(catalog, never()).release(any());
        }

        @Test
        @DisplayName("a shipped order cannot be cancelled")
        void cannotCancelShipped() {
            when(orderRepository.findWithItemsById(1L))
                    .thenReturn(Optional.of(orderWithStatus(OrderStatus.SHIPPED)));

            assertThatThrownBy(() -> orderService.cancel(CUSTOMER, 1L))
                    .isInstanceOf(OrderNotAllowedException.class)
                    .hasMessageContaining("already shipped");
        }
    }
}
