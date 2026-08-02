package com.example.payment.client;

import com.example.payment.exception.OrderServiceUnavailableException;
import com.example.payment.exception.PaymentNotAllowedException;
import com.example.payment.exception.ResourceNotFoundException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Translates order-service's failures into this service's vocabulary — same reasoning as
 * order-service's decoder, and the same reminder attached: an ErrorDecoder only ever sees HTTP
 * responses. A refused connection or a timeout never reaches it, which is why
 * {@code GlobalExceptionHandler} also maps {@code FeignException} to 503.
 */
@Configuration
@Slf4j
public class OrderClientErrorConfig {

    @Bean
    public ErrorDecoder orderClientErrorDecoder() {
        return (String methodKey, Response response) -> switch (response.status()) {
            case 404 -> new ResourceNotFoundException("Order not found (via order-service)");
            case 409 -> new PaymentNotAllowedException(
                    "order-service rejected the request: the order is not in a payable state");
            case 401, 403 -> {
                log.error("order-service rejected payment-service's credentials on {}", methodKey);
                yield new OrderServiceUnavailableException(
                        "order-service rejected this service's credentials", null);
            }
            default -> {
                log.error("order-service returned {} on {}", response.status(), methodKey);
                yield new OrderServiceUnavailableException(
                        "order-service is unavailable (HTTP " + response.status() + ")", null);
            }
        };
    }
}
