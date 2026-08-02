package com.example.analytics.listener;

import com.example.analytics.service.SalesTally;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.test.context.EmbeddedKafka;
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
@Import(OrderPlacedListenerTest.RawJsonProducer.class)
@DisplayName("OrderPlaced, counted")
class OrderPlacedListenerTest {

    @Autowired
    private KafkaTemplate<String, String> rawJson;

    @Autowired
    private SalesTally tally;

    @Test
    @DisplayName("revenue and copies sold come off the event, not out of a database call")
    void countsWhatTheEventCarries() {
        // Everything needed to count a sale travels in the message. That is why this service can be
        // written, deployed and restarted without order-service or book-service knowing it exists, and
        // why it keeps working when both are down.
        rawJson.send("bookstore.order.placed", "500", """
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
                .untilAsserted(() -> assertThat(tally.ordersCounted()).isEqualTo(1));

        assertThat(tally.currentRevenue()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(tally.copiesSoldOf(11L)).isEqualTo(2);
    }

    /** See notification-service's copy: the application's own template would serialise this to a
     *  quoted JSON string literal. The point is to publish the bytes order-service publishes. */
    @TestConfiguration
    static class RawJsonProducer {

        @Bean
        public KafkaTemplate<String, String> rawJsonTemplate(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {

            Map<String, Object> config = new HashMap<>();
            config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

            ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(config);
            return new KafkaTemplate<>(factory);
        }
    }
}
