package com.example.order.event;

import com.example.order.entity.Order;
import com.example.order.entity.OrderItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Announces that an order was placed.
 *
 * <p>The whole point of Step 7 is what this class does <em>not</em> do: it does not wait for anybody to
 * react. Before this, adding "send a confirmation email" to an order meant another synchronous call on
 * the critical path — another thing that can be slow, another thing that can fail, and another reason a
 * customer's order does not go through. Now the side effect is somebody else's problem, and the order
 * request is finished the moment the order is durable.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafka;

    @Value("${app.kafka.topics.order-placed}")
    private String orderPlacedTopic;

    /**
     * Publishes {@link OrderPlaced}, keyed by order id.
     *
     * <p><strong>The key is not decoration.</strong> Kafka guarantees ordering within a partition and
     * nowhere else, and the key is what chooses the partition. Key by order id and every event about
     * one order lands on one partition, so a consumer sees them in the order they happened. Send with a
     * null key and they round-robin across partitions, where "order placed" and "order cancelled" can
     * be processed by different threads in either order — a bug that appears only under load, only
     * sometimes, and never in a single-partition development topic.
     *
     * <p><strong>Fire and forget, and that is a real hole.</strong> The order is already committed when
     * this runs. If the broker is unreachable, or this process dies in between, the order exists and
     * nothing was ever announced — no error, no retry, no trace. A database write and a Kafka send
     * cannot be one atomic act, and pretending otherwise is the classic dual-write bug. The proper fix
     * is a transactional outbox: write the event into the order's own database in the same transaction
     * as the order, and let a poller publish it. That is deliberately not built here; what is built is
     * the demonstration of the hole, in the Step 7 README.
     *
     * <p>Failures are logged and swallowed rather than thrown. Throwing would fail the customer's order
     * <em>after</em> it has been placed and stock reserved, which converts a missing email into a lie.
     */
    public void orderPlaced(Order order) {
        OrderPlaced event = new OrderPlaced(
                order.getId(),
                order.getUserId(),
                order.getTotalPrice(),
                toItems(order.getItems()),
                order.getCreatedAt());

        try {
            kafka.send(orderPlacedTopic, String.valueOf(order.getId()), event);
            log.info("Published OrderPlaced for order {} to {}", order.getId(), orderPlacedTopic);
        } catch (RuntimeException ex) {
            log.error("Order {} was placed but OrderPlaced could not be published ({}). "
                            + "Downstream consumers will never hear about it.",
                    order.getId(), ex.toString());
        }
    }

    private List<OrderPlaced.Item> toItems(List<OrderItem> items) {
        return items.stream()
                .map(i -> new OrderPlaced.Item(
                        i.getBookId(), i.getBookTitle(), i.getQuantity(), i.getUnitPrice()))
                .toList();
    }
}
