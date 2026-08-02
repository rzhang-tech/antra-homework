package com.example.order.client;

import com.example.order.dto.BookSnapshot;
import com.example.order.exception.CatalogUnavailableException;
import com.example.order.exception.OrderNotAllowedException;
import com.example.order.exception.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The only thing in this service that talks to the catalog.
 *
 * <p>{@link BookClient} describes <em>what</em> the HTTP calls are; this class decides <em>how they may
 * fail</em>. Keeping the two apart matters because the two operations below need different failure
 * policies, and a policy applied to the whole Feign client could not distinguish them.
 *
 * <h2>Why the read may be retried and the write may not</h2>
 *
 * <p>{@code findById} is a GET: idempotent, free of side effects, safe to repeat. A dropped packet or a
 * pod restarting mid-request is exactly the kind of blip a retry exists for.
 *
 * <p>{@code purchase} decrements stock. Retrying it is not a harmless repeat — it is <strong>selling the
 * same book twice</strong>. And the case that makes this dangerous is the one that looks like a
 * failure: book-service commits the decrement and then the response is lost on the way back. The caller
 * sees a timeout and cannot tell it apart from "nothing happened". Retrying takes another copy off the
 * shelf; not retrying leaves an order that failed with stock already gone. There is no correct answer
 * available at this layer — only a choice of which error to prefer, and losing stock is recoverable
 * where overselling a customer is not.
 *
 * <p>The real fix is to make the operation idempotent — a request id book-service remembers, so a repeat
 * is recognised rather than reapplied. That is 5d's work, and it is the reason 5d exists.
 *
 * <h2>Why the fallback does not invent data</h2>
 *
 * <p>Circuit-breaker tutorials tend to return a cached or dummy value from the fallback. For a price and
 * a stock level that would be indefensible: charging an invented price, or selling stock that may not
 * exist, is far worse than an error. The fallback here converts the failure into
 * {@link CatalogUnavailableException} — a clean 503 — <strong>immediately</strong>.
 *
 * <p>The value is not the substitute value. It is that once the circuit is open the caller finds out in
 * microseconds instead of waiting out a 3-second timeout, so a catalog outage stops consuming
 * order-service's threads. Failing fast is the fallback.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogGateway {

    /** Circuit-breaker instance name; the configuration under {@code resilience4j.*} matches it. */
    public static final String CATALOG = "catalog";

    private final BookClient bookClient;

    /**
     * Price and stock for one book.
     *
     * <p>Retried, then guarded by the circuit breaker.
     *
     * <p><strong>The nesting is configured, not default.</strong> Resilience4j puts Retry
     * <em>outside</em> CircuitBreaker unless told otherwise, which breaks this in two ways: one retried
     * call records three separate failures against the breaker, and an open circuit's refusal travels
     * out to Retry, which retries being-told-no through the full backoff — "fail fast" measured 619 ms.
     *
     * <p>The aspect orders in {@code application.yml} invert it to {@code CircuitBreaker(Retry(call))}:
     * three attempts count as one failure, and an open circuit refuses immediately with no retry in the
     * path.
     */
    @CircuitBreaker(name = CATALOG, fallbackMethod = "catalogDown")
    @Retry(name = CATALOG)
    public BookSnapshot findById(Long id) {
        return bookClient.findById(id);
    }

    /**
     * Reserves stock. Circuit-broken, deliberately <strong>not</strong> retried.
     *
     * <p>The circuit breaker still applies: if the catalog is known to be down there is no point
     * attempting a write either, and failing instantly is strictly better than failing slowly.
     */
    @CircuitBreaker(name = CATALOG, fallbackMethod = "catalogDown")
    public BookSnapshot purchase(Long id, int quantity) {
        return bookClient.purchase(id, Map.of("quantity", quantity));
    }

    /**
     * Fallback for {@link #findById}.
     *
     * <p>A fallback method takes the original arguments plus the {@code Throwable}.
     *
     * <p>Business answers — a missing book, insufficient stock — do arrive here and are rethrown
     * untouched by {@link #rethrowIfBusinessAnswer}. They are separately excluded from the failure rate
     * and from retry by {@code ignore-exceptions} in configuration, which is not a detail: a circuit
     * breaker that counts 404s as failures opens under entirely healthy traffic, taking the catalog
     * "down" because customers asked for books that do not exist.
     */
    @SuppressWarnings("unused")
    private BookSnapshot catalogDown(Long id, Throwable cause) {
        rethrowIfBusinessAnswer(cause);
        log.warn("Catalog call for book {} failed ({}); returning 503", id, cause.toString());
        throw new CatalogUnavailableException(
                "The catalog is unavailable (book " + id + ")", cause);
    }

    /** Fallback for {@link #purchase}. Same reasoning, different signature. */
    @SuppressWarnings("unused")
    private BookSnapshot catalogDown(Long id, int quantity, Throwable cause) {
        rethrowIfBusinessAnswer(cause);
        log.warn("Stock reservation for book {} x{} failed ({}); returning 503",
                id, quantity, cause.toString());
        throw new CatalogUnavailableException(
                "The catalog is unavailable (book " + id + ")", cause);
    }

    /**
     * Lets a real answer through untouched.
     *
     * <p>{@code ignore-exceptions} keeps these out of the failure <em>rate</em>, but it does not stop
     * the fallback method running — a fallback catches everything the guarded method throws. Without
     * this check, "no such book" reached the customer as **503 Service Unavailable**: the catalog
     * answered correctly and order-service reported it as an outage.
     *
     * <p>A fallback exists for failures. A 404 is not a failure, it is the answer.
     */
    private static void rethrowIfBusinessAnswer(Throwable cause) {
        if (cause instanceof ResourceNotFoundException || cause instanceof OrderNotAllowedException) {
            throw (RuntimeException) cause;
        }
    }
}
