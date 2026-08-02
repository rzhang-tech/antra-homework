package com.example.order.service;

import com.example.order.client.CatalogGateway;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Finishes what a crashed process left half-done.
 *
 * <p>This is the piece that makes the rest a saga rather than a sequence of hopeful calls. Everything in
 * {@code OrderServiceImpl.place} handles failures it is <em>present</em> for: an exception it can catch,
 * a compensating call it can make. None of it survives the process being killed between two steps — and
 * that is precisely when inconsistency is created and nobody is left to notice.
 *
 * <p>An order stuck in {@link OrderStatus#PENDING} is exactly that footprint. A healthy order is PENDING
 * for milliseconds; one that has been PENDING for minutes belongs to a saga whose orchestrator died.
 * The reservation ids were committed with the order in step 2, so unwinding needs no guessing about what
 * to give back.
 *
 * <p><strong>The order of operations matters here too.</strong> Reservations are released <em>before</em>
 * the order is marked FAILED. If the job itself dies midway, the order is still PENDING and the next run
 * tries again — release is idempotent, so a second attempt is harmless. Marking FAILED first would risk
 * the opposite: an order that looks resolved while its stock is still held, and no state left to find
 * it by.
 *
 * <p>What this deliberately does not attempt: deciding that a PENDING order should be <em>completed</em>
 * rather than unwound. Rolling forward needs to know whether each reservation actually took effect,
 * which means asking book-service about each id. Unwinding needs no such knowledge — releasing a
 * reservation that was never made is a no-op. Choosing the direction that requires less information is
 * usually the right call in recovery code.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderRecoveryJob {

    private final OrderRepository orderRepository;
    private final CatalogGateway catalog;
    private final OrderTransactions orderTransactions;

    /**
     * How long an order may sit in PENDING before it is presumed abandoned.
     *
     * <p>Comfortably longer than the worst honest case — every Feign timeout plus retries, several times
     * over. Too short and the job unwinds orders that were merely slow, which is a self-inflicted
     * outage; too long and stock sits held for no reason. Slow is recoverable, so this errs long.
     */
    @Value("${app.saga.pending-timeout:PT2M}")
    private Duration pendingTimeout;

    @Scheduled(fixedDelayString = "${app.saga.recovery-interval-ms:30000}")
    public void unwindAbandonedOrders() {
        Instant cutoff = Instant.now().minus(pendingTimeout);
        List<Order> abandoned = orderRepository
                .findByStatusAndStateChangedAtBefore(OrderStatus.PENDING, cutoff);

        if (abandoned.isEmpty()) {
            return;
        }

        log.warn("Saga recovery: {} order(s) stuck in PENDING since before {}. Unwinding.",
                abandoned.size(), cutoff);

        for (Order order : abandoned) {
            unwind(order);
        }
    }

    /**
     * Not transactional as a whole, for the same reason {@code place} is not: it makes network calls,
     * and a transaction held across them would prove nothing while holding a connection.
     */
    private void unwind(Order order) {
        boolean allReleased = true;

        for (var item : order.getItems()) {
            if (item.getReservationId() == null) {
                continue;   // crashed before an id was assigned; nothing was reserved
            }
            try {
                catalog.release(item.getReservationId());
                log.info("Saga recovery: released reservation {} ({} x book {}) from order {}",
                        item.getReservationId(), item.getQuantity(), item.getBookId(), order.getId());
            } catch (RuntimeException ex) {
                // Leave the order PENDING so the next run retries. Releasing twice is harmless;
                // giving up is not.
                allReleased = false;
                log.error("Saga recovery: could not release reservation {} for order {} ({}). "
                                + "Leaving the order PENDING to retry.",
                        item.getReservationId(), order.getId(), ex.toString());
            }
        }

        if (allReleased) {
            orderTransactions.markFailed(order.getId());
            log.warn("Saga recovery: order {} unwound and marked FAILED", order.getId());
        }
    }

    /** Exposed for tests and for an operator wanting to run recovery on demand. */
    @Transactional(readOnly = true)
    public long countAbandoned() {
        return orderRepository
                .findByStatusAndStateChangedAtBefore(OrderStatus.PENDING, Instant.now().minus(pendingTimeout))
                .size();
    }
}
