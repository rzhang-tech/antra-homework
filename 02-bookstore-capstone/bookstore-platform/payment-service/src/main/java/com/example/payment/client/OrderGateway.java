package com.example.payment.client;

import com.example.payment.dto.OrderSnapshot;
import com.example.payment.exception.OrderServiceUnavailableException;
import com.example.payment.exception.PaymentNotAllowedException;
import com.example.payment.exception.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Everything payment-service says to order-service, with its failure policy attached.
 *
 * <p>Both calls are retried, which is a different conclusion from order-service's gateway — there, the
 * stock write was retried only once a reservation id made it idempotent. Here both are safe by nature:
 * the GET is a read, and {@code markPaid} is idempotent on order-service's side because an order already
 * PAID is returned unchanged. Retrying is not a risk taken, it is a property established upstream.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderGateway {

    public static final String ORDERS = "orders";

    private final OrderClient orderClient;

    @CircuitBreaker(name = ORDERS, fallbackMethod = "ordersDown")
    @Retry(name = ORDERS)
    public OrderSnapshot findById(Long orderId) {
        return orderClient.findById(orderId);
    }

    @CircuitBreaker(name = ORDERS, fallbackMethod = "ordersDown")
    @Retry(name = ORDERS)
    public OrderSnapshot markPaid(Long orderId) {
        return orderClient.markPaid(orderId);
    }

    @SuppressWarnings("unused")
    private OrderSnapshot ordersDown(Long orderId, Throwable cause) {
        // Business answers pass through untouched — the fallback is for failures, and a 404 or a 409
        // is an answer. Same trap as CatalogGateway, same fix.
        if (cause instanceof ResourceNotFoundException || cause instanceof PaymentNotAllowedException) {
            throw (RuntimeException) cause;
        }
        log.warn("order-service call for order {} failed ({})", orderId, cause.toString());
        throw new OrderServiceUnavailableException(
                "order-service is unavailable (order " + orderId + ")", cause);
    }
}
