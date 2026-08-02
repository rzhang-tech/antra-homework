package com.example.order.client;

import com.example.order.exception.CatalogUnavailableException;
import com.example.order.exception.OrderNotAllowedException;
import com.example.order.exception.ResourceNotFoundException;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Translates book-service's failures into this service's vocabulary.
 *
 * <p>Feign's default turns every non-2xx into a {@code FeignException}, which reaches the client as a
 * 500 — so "you asked for a book that does not exist" and "the catalog is on fire" become the same
 * unhelpful answer. Worse, a 404 from a downstream service reported as a 500 sends someone debugging
 * the wrong system.
 *
 * <p>The mapping keeps the meaning and changes the subject: the customer asked order-service for
 * something, and the reply should describe <em>their</em> request.
 *
 * <table>
 *   <tr><th>book-service says</th><th>order-service raises</th><th>client sees</th></tr>
 *   <tr><td>404</td><td>ResourceNotFoundException</td><td>404 — no such book</td></tr>
 *   <tr><td>409</td><td>OrderNotAllowedException</td><td>409 — not enough stock</td></tr>
 *   <tr><td>401 / 403</td><td>CatalogUnavailable</td><td>503 — our token was rejected; a
 *       platform misconfiguration, not something the customer can fix</td></tr>
 *   <tr><td>5xx / timeout</td><td>CatalogUnavailable</td><td>503 — try again</td></tr>
 * </table>
 *
 * <p>The 401/403 row is worth pausing on. Forwarding a valid customer token should never produce one;
 * if it does, either propagation is broken or the services disagree about the signing key. Passing it
 * through as 401 would tell the customer to log in again, which would not help and would hide a
 * platform fault.
 */
@Configuration
@Slf4j
public class BookClientErrorConfig {

    @Bean
    public ErrorDecoder bookClientErrorDecoder() {
        return (String methodKey, Response response) -> switch (response.status()) {
            case 404 -> new ResourceNotFoundException("Book not found (via book-service)");
            case 409 -> new OrderNotAllowedException(
                    "book-service rejected the request: not enough stock, or the item changed");
            case 401, 403 -> {
                log.error("book-service rejected order-service's credentials on {} — check token "
                        + "propagation and that both services share a signing key", methodKey);
                yield new CatalogUnavailableException(
                        "The catalog rejected this service's credentials", null);
            }
            default -> {
                log.error("book-service returned {} on {}", response.status(), methodKey);
                yield new CatalogUnavailableException(
                        "The catalog is unavailable (HTTP " + response.status() + ")", null);
            }
        };
    }
}
