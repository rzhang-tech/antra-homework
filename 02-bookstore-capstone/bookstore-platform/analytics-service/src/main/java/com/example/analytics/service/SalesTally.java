package com.example.analytics.service;

import com.example.analytics.event.OrderPlaced;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A running total of what the shop has sold.
 *
 * <p>Deliberately the kind of work a duplicate corrupts. notification-service can process the same
 * event twice and the worst outcome is a second email — visible, annoying, and obviously wrong to the
 * person receiving it. Here a duplicate adds real money to a total that nobody can tell is wrong by
 * looking at it. That difference is the reason Step 7c exists, and it is why "at-least-once delivery"
 * is a statement about <em>your</em> code rather than about the broker.
 *
 * <h2>In memory, and why that is not the point being made</h2>
 *
 * <p>A real analytics service writes to a warehouse, and losing the tally on restart would be a defect.
 * Here it is a deliberate limit of scope: the lesson is consumer groups and idempotency, and a database
 * would add a schema and a migration without adding either. What this does keep is the property that
 * matters for the demonstration — a number that is <em>wrong</em> after a duplicate, not merely a log
 * line printed twice.
 */
@Service
@Slf4j
public class SalesTally {

    private final AtomicLong ordersCounted = new AtomicLong();
    private final Map<Long, Long> copiesSoldByBook = new ConcurrentHashMap<>();

    /**
     * Guarded rather than atomic, because "add to revenue and to the count" has to be one step.
     *
     * <p>A consumer group hands each partition to exactly one member, so two threads never process the
     * same partition — but this service consumes three partitions, and the container runs them
     * concurrently. Two orders on two partitions can land here at the same instant.
     */
    private final Object lock = new Object();
    private BigDecimal revenue = BigDecimal.ZERO;

    public void record(OrderPlaced event) {
        synchronized (lock) {
            revenue = revenue.add(event.totalPrice());
        }
        ordersCounted.incrementAndGet();

        for (OrderPlaced.Item item : event.items()) {
            copiesSoldByBook.merge(item.bookId(), (long) item.quantity(), Long::sum);
        }

        log.info("TALLY after order {}: {} order(s), revenue {}, best seller {}",
                event.orderId(), ordersCounted.get(), currentRevenue(), bestSeller());
    }

    public BigDecimal currentRevenue() {
        synchronized (lock) {
            return revenue;
        }
    }

    public long ordersCounted() {
        return ordersCounted.get();
    }

    public long copiesSoldOf(Long bookId) {
        return copiesSoldByBook.getOrDefault(bookId, 0L);
    }

    private String bestSeller() {
        return copiesSoldByBook.entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(e -> "book " + e.getKey() + " (" + e.getValue() + " copies)")
                .orElse("nothing yet");
    }
}
