package com.example.analytics.listener;

import com.example.analytics.service.SalesTally;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * That this service reads the same topic as notification-service, and counts what it reads.
 *
 * <p>Nothing here proves the two services are independent — a test with one consumer cannot. That
 * property is demonstrated against the running platform instead (see the Step 7 README: two groups,
 * separate offsets, one replaying the topic from the beginning while the other sent no second email).
 * What a test <em>can</em> pin is that the arithmetic is right, which is what makes the redelivery
 * problem in Step 7c visible as a wrong number rather than a duplicated log line.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = "bookstore.order.placed")
@ActiveProfiles("test")
@DisplayName("OrderPlaced, counted")
class OrderPlacedListenerTest {

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private SalesTally tally;

    @Test
    @DisplayName("revenue and copies sold come off the event, not out of a database call")
    void countsWhatTheEventCarries() {
        // Everything needed to count a sale travels in the message. That is why this service can be
        // written, deployed and restarted without order-service or book-service knowing it exists, and
        // why it keeps working when both are down.
        //
        // Asserted as a DELTA, and against this test's own book id. The tally is a singleton shared
        // with every other test in this class - absolute assertions passed only in the order the tests
        // happened to run first, which is the classic shared-fixture trap and worth not shipping.
        BigDecimal revenueBefore = tally.currentRevenue();

        publishRaw("bookstore.order.placed", "500", """
                {
                  "orderId": 500,
                  "userId": 3,
                  "totalPrice": 42.50,
                  "items": [
                    {"bookId": 11, "title": "Clean Code", "quantity": 2, "unitPrice": 21.25}
                  ],
                  "placedAt": "2026-08-02T18:05:47.442536Z"
                }
                """);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(tally.copiesSoldOf(11L)).isEqualTo(2));

        assertThat(tally.currentRevenue().subtract(revenueBefore))
                .isEqualByComparingTo(new BigDecimal("42.50"));
    }


    @Test
    @DisplayName("a redelivered event is counted once, not twice")
    void redeliveryDoesNotDoubleCount() {
        // At-least-once is not a caveat in the documentation - it is the normal operating mode. A
        // rebalance, a slow poll, a restarted pod or a producer retry all produce this exact message
        // twice, and without a guard the second copy is indistinguishable from a second sale.
        //
        // Note what makes this the dangerous kind of bug: nothing errors, nothing is logged as wrong,
        // and the resulting number looks exactly like a number. notification-service sending two
        // emails is visible to the customer; revenue counted twice is visible to nobody.
        String sameOrderTwice = """
                {
                  "orderId": 501,
                  "userId": 3,
                  "totalPrice": 30.00,
                  "items": [
                    {"bookId": 12, "title": "Effective Java", "quantity": 1, "unitPrice": 30.00}
                  ],
                  "placedAt": "2026-08-02T18:05:47.442536Z"
                }
                """;

        BigDecimal revenueBefore = tally.currentRevenue();

        publishRaw("bookstore.order.placed", "501", sameOrderTwice);
        publishRaw("bookstore.order.placed", "501", sameOrderTwice);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(tally.copiesSoldOf(12L)).isEqualTo(1));

        // And it STAYS right. Asserting once the moment the count reaches 1 would pass without any
        // guard at all, simply by reading the tally in the gap between the two deliveries.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    assertThat(tally.copiesSoldOf(12L)).isEqualTo(1);
                    assertThat(tally.currentRevenue().subtract(revenueBefore))
                            .isEqualByComparingTo(new BigDecimal("30.00"));
                });
    }

    /**
     * Publishes the exact bytes another service publishes.
     *
     * <p>Built here rather than registered as a bean, and that is not a style choice. A
     * {@code KafkaTemplate} bean of any generic type suppresses Spring Boot's auto-configured one
     * ({@code @ConditionalOnMissingBean(KafkaTemplate.class)} matches by raw type), which quietly
     * removed the template the dead letter recoverer needs — a test fixture breaking production
     * wiring, discovered only when Step 7d added the recoverer.
     */
    private void publishRaw(String topic, String key, String json) {
        Map<String, Object> props = new HashMap<>(KafkaTestUtils.producerProps(broker));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        try (Producer<String, String> producer =
                     new DefaultKafkaProducerFactory<String, String>(props).createProducer()) {
            producer.send(new ProducerRecord<>(topic, key, json));
            producer.flush();
        }
    }
}
