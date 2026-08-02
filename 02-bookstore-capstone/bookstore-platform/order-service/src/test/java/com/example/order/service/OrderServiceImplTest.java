package com.example.order.service;

import com.example.order.client.BookClient;
import com.example.order.dto.BookSnapshot;
import com.example.order.dto.OrderItemRequestDto;
import com.example.order.dto.OrderRequestDto;
import com.example.order.dto.OrderResponseDto;
import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import com.example.order.entity.OrderStatus;
import com.example.order.exception.OrderNotAllowedException;
import com.example.order.exception.ResourceNotFoundException;
import com.example.order.repository.OrderRepository;
import com.example.order.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for placing and reading orders, with book-service mocked.
 *
 * <p>Mocking the Feign client is the right level here: these tests are about order-service's rules —
 * what it validates, what it captures, who may see what. Whether the HTTP call itself behaves is a
 * different question, answered by the WireMock-backed tests in 5c.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceImpl")
class OrderServiceImplTest {

    @Mock private OrderRepository orderRepository;
    @Mock private BookClient bookClient;

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

    private void expectSave() {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("place")
    class Place {

        @Test
        @DisplayName("captures the price at order time rather than referencing it")
        void capturesPrice() {
            when(bookClient.findById(1L)).thenReturn(book(1L, "Clean Code", "42.50", 10));
            expectSave();

            OrderResponseDto result = orderService.place(CUSTOMER, order(1L, 2));

            assertThat(result.items()).hasSize(1);
            assertThat(result.items().getFirst().unitPrice()).isEqualByComparingTo("42.50");
            assertThat(result.items().getFirst().bookTitle()).isEqualTo("Clean Code");
            assertThat(result.totalPrice()).isEqualByComparingTo("85.00");
        }

        @Test
        @DisplayName("records the customer from the token, never from the request")
        void recordsCustomerFromToken() {
            when(bookClient.findById(1L)).thenReturn(book(1L, "Clean Code", "42.50", 10));
            expectSave();

            orderService.place(CUSTOMER, order(1L, 1));

            ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
            verify(orderRepository).save(saved.capture());
            assertThat(saved.getValue().getUserId()).isEqualTo(7L);
            assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @Test
        @DisplayName("reserves stock in book-service before writing the order")
        void reservesStock() {
            when(bookClient.findById(1L)).thenReturn(book(1L, "Clean Code", "42.50", 10));
            expectSave();

            orderService.place(CUSTOMER, order(1L, 3));

            verify(bookClient).purchase(1L, Map.of("quantity", 3));
        }

        @Test
        @DisplayName("rejects an unknown book without reserving anything")
        void rejectsUnknownBook() {
            when(bookClient.findById(99L)).thenReturn(null);

            assertThatThrownBy(() -> orderService.place(CUSTOMER, order(99L, 1)))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(bookClient, never()).purchase(anyLong(), any());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses to oversell before calling the catalog's write endpoint")
        void refusesOverselling() {
            when(bookClient.findById(1L)).thenReturn(book(1L, "Clean Code", "42.50", 3));

            assertThatThrownBy(() -> orderService.place(CUSTOMER, order(1L, 4)))
                    .isInstanceOf(OrderNotAllowedException.class)
                    .hasMessageContaining("only 3");

            // Reading is free; reserving is not. Validating everything first is what keeps a bad
            // order from leaving half-applied changes in another service's database.
            verify(bookClient, never()).purchase(anyLong(), any());
        }

        @Test
        @DisplayName("collapses two lines for the same book into one reservation")
        void collapsesDuplicateLines() {
            when(bookClient.findById(1L)).thenReturn(book(1L, "Clean Code", "42.50", 10));
            expectSave();

            orderService.place(CUSTOMER, new OrderRequestDto(List.of(
                    new OrderItemRequestDto(1L, 2),
                    new OrderItemRequestDto(1L, 3))));

            // One call for 5, not two calls that each pass their own stock check while together
            // exceeding it.
            verify(bookClient).purchase(1L, Map.of("quantity", 5));
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
            return Order.builder()
                    .id(1L).userId(7L).status(status)
                    .totalPrice(new BigDecimal("42.50")).version(0L)
                    .build();
        }

        @Test
        @DisplayName("a pending order becomes CANCELLED")
        void cancelsPending() {
            when(orderRepository.findWithItemsById(1L))
                    .thenReturn(Optional.of(orderWithStatus(OrderStatus.PENDING)));

            assertThat(orderService.cancel(CUSTOMER, 1L).status()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("cancelling twice is not an error — the second call is a no-op")
        void cancelIsIdempotent() {
            when(orderRepository.findWithItemsById(1L))
                    .thenReturn(Optional.of(orderWithStatus(OrderStatus.CANCELLED)));

            assertThat(orderService.cancel(CUSTOMER, 1L).status()).isEqualTo(OrderStatus.CANCELLED);
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
