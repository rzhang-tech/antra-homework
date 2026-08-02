package com.example.analytics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Remembers which orders have already been counted.
 *
 * <h2>Why this has to exist at all</h2>
 *
 * <p>Kafka delivers at least once. Not because it is unreliable — because the alternative is worse.
 * A consumer processes a record and then commits its offset; if it dies in between, the next consumer
 * to own that partition starts from the last committed offset and delivers the record again. Committing
 * <em>before</em> processing would remove duplicates and introduce silent loss instead, which is why
 * {@code enable-auto-commit: false} is set in the shared config and why this class is the price of that
 * choice. A rebalance, a slow poll, a restarted pod and a producer retry all produce the same effect.
 *
 * <p><strong>The broker cannot fix this for you.</strong> Producer idempotence removes duplicates one
 * producer session would create; Kafka transactions give exactly-once between topics. Neither covers
 * "this consumer added a number to a total twice", because that side effect lives outside Kafka.
 * At-least-once is a statement about your code.
 *
 * <h2>The key is the order id, not a message id</h2>
 *
 * <p>The same reasoning as payment-service's {@code order_id UNIQUE} in 5e: a natural key beats a
 * synthetic one because a caller cannot forget to send it and cannot send a fresh one by mistake. An
 * event carrying a per-message UUID would be deduplicated only against <em>identical retransmissions</em>
 * — republish the same order after a producer restart with a new UUID and it counts twice again. One
 * order is placed once, so "have I counted order 4242?" is the question that is actually being asked.
 *
 * <h2>In memory, bounded, and honest about it</h2>
 *
 * <p>This store is exactly as durable as the state it protects, which is the property that matters:
 * {@link SalesTally} is in memory too, so a restart loses the tally and the guard together and they
 * cannot disagree. A guard that outlived the state would suppress the very replay needed to rebuild it.
 *
 * <p>It is bounded because an unbounded set of every id ever seen is a memory leak with a long fuse —
 * fine in a demo, an out-of-memory error in month four. The bound is the real limitation: an order
 * older than the last {@value #REMEMBERED} would be counted twice if it were redelivered now. That is
 * acceptable here because redelivery happens within seconds of the original, and unacceptable in a
 * system where it might not — where the guard belongs in a database table with a retention policy, or
 * in Redis with a TTL longer than the worst redelivery window.
 */
@Component
@Slf4j
public class ProcessedOrders {

    /** Comfortably more than any redelivery window this platform can produce, and small enough to hold. */
    private static final int REMEMBERED = 10_000;

    private final Set<Long> seen = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > REMEMBERED;
                }
            }));

    /**
     * True the first time an order is seen, false every time after.
     *
     * <p>Deliberately one call that both asks and records. Two calls — {@code hasSeen} then
     * {@code markSeen} — is a check-then-act race, and this service consumes three partitions
     * concurrently, so the race is real rather than theoretical. {@code Set.add} is the atomic
     * version of exactly that pair.
     */
    public boolean firstTimeSeeing(Long orderId) {
        boolean added = seen.add(orderId);
        if (!added) {
            log.info("Order {} has already been counted; ignoring redelivery", orderId);
        }
        return added;
    }
}
