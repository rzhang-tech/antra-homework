package com.example.notification.listener;

import com.example.notification.event.OrderPlaced;
import com.example.notification.service.ConfirmationSender;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * The one thing about this service that only a real broker can prove.
 *
 * <p>The behaviour — what a confirmation says — is an ordinary method on {@link ConfirmationSender} and
 * needs no Kafka. What needs Kafka is the <strong>cross-service contract</strong>: JSON written by
 * order-service, which has never heard of this service's classes, binding to this service's own copy of
 * the record.
 *
 * <p>That is the piece that broke first and would break again. Spring's {@code JsonSerializer} writes
 * the producer's fully-qualified class name into a {@code __TypeId__} header unless told not to, and a
 * consumer that trusts it tries to instantiate {@code com.example.order.event.OrderPlaced} — a class
 * that does not exist here, and must not (D12). So this test publishes exactly what order-service
 * publishes: raw JSON, no type header, from a producer that shares no code with the consumer.
 */
@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = "bookstore.order.placed")
@ActiveProfiles("test")
@DisplayName("OrderPlaced, arriving from another service")
class OrderPlacedListenerTest {

    @MockitoBean
    private ConfirmationSender sender;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    @DisplayName("binds another service's JSON to this service's own copy of the record")
    void bindsForeignJsonWithNoTypeHeader() {
        // Written by hand rather than by serialising the consumer's own record, on purpose. Serialising
        // the local class would make this a test that Jackson round-trips, which nobody doubts. What is
        // under test is that THIS shape of JSON — the producer's — is understood here.
        String json = """
                {
                  "orderId": 4242,
                  "userId": 7,
                  "totalPrice": 20.00,
                  "items": [
                    {"bookId": 1, "title": "Clean Code", "quantity": 2, "unitPrice": 10.00}
                  ],
                  "placedAt": "2026-08-02T14:47:56.977745Z"
                }
                """;

        publishRaw("bookstore.order.placed", "4242", json);

        OrderPlaced received = awaitEventWithOrderId(4242L);

        assertThat(received.userId()).isEqualTo(7L);
        assertThat(received.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Clean Code");
            assertThat(item.quantity()).isEqualTo(2);
        });

        // The timestamp is the producer's, not this consumer's. A consumer running an hour behind still
        // has to say when the order was actually placed — which is why it travels in the payload rather
        // than being read off the record's own timestamp.
        assertThat(received.placedAt()).hasToString("2026-08-02T14:47:56.977745Z");
    }

    @Test
    @DisplayName("a field the consumer has never heard of is ignored, not fatal")
    void toleratesFieldsAddedByTheProducer() {
        // The whole reason each service keeps its own copy of the contract: order-service must be able
        // to add a field and deploy without every consumer being rebuilt first. If this fails, adding
        // one field to OrderPlaced takes the notification pipeline down.
        String jsonFromANewerProducer = """
                {
                  "orderId": 99,
                  "userId": 1,
                  "totalPrice": 5.00,
                  "items": [],
                  "placedAt": "2026-08-02T14:47:56.977745Z",
                  "giftMessage": "added in some later release",
                  "channel": "MOBILE"
                }
                """;

        publishRaw("bookstore.order.placed", "99", jsonFromANewerProducer);

        assertThat(awaitEventWithOrderId(99L).items()).isEmpty();
    }

    /** Waits for the listener to hand the sender an event with this id, and returns it. */
    private OrderPlaced awaitEventWithOrderId(long orderId) {
        ArgumentCaptor<OrderPlaced> captor = ArgumentCaptor.forClass(OrderPlaced.class);
        verify(sender, timeout(20_000).atLeastOnce()).send(captor.capture());

        return captor.getAllValues().stream()
                .filter(e -> e.orderId() == orderId)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no OrderPlaced with id " + orderId + " reached the sender; got "
                                + captor.getAllValues()));
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
