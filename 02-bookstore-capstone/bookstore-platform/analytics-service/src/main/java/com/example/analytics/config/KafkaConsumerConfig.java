package com.example.analytics.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;

/**
 * How bytes on a topic become an {@link com.example.analytics.event.OrderPlaced}.
 *
 * <p>The obvious setup — {@code JsonDeserializer} on the consumer — does not survive the service split,
 * and the way it fails is worth knowing because it looks like a serialization bug and is not.
 * {@code JsonSerializer} writes the producer's fully-qualified class name into a {@code __TypeId__}
 * header, and {@code JsonDeserializer} obeys it. So order-service publishes
 * {@code com.example.order.event.OrderPlaced} and this service is asked to instantiate a class it does
 * not have, because its own copy lives in {@code com.example.analytics.event}. The fix people reach
 * for is to share a jar of event classes, which recreates precisely the coupling D12 removed.
 *
 * <p>Type headers are therefore switched off at the producer, and the type is decided <em>here</em>, by
 * the signature of the {@code @KafkaListener} method: the message converter reads the JSON into
 * whatever the method declares it wants. The consumer chooses how to interpret the bytes, which is the
 * correct division of authority — a producer publishes facts, not Java classes — and it is what lets one
 * service listen to two topics carrying two different event types without any of this becoming a
 * special case.
 */
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public RecordMessageConverter jsonMessageConverter() {
        // Spring Boot leaves Jackson's FAIL_ON_UNKNOWN_PROPERTIES off, which is what makes a producer
        // adding a field an additive change rather than an outage in a consumer nobody rebuilt.
        // Worth knowing it is a default and not a decision anyone here made - a project that turns it
        // on globally for stricter API parsing would break every consumer on the platform at once.
        return new StringJsonMessageConverter();
    }
}
