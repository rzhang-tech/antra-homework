package com.example.analytics.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * What happens when a message cannot be processed.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>Kafka gives ordering within a partition, and the container honours it: a record that keeps
 * throwing is retried forever and <strong>every later record on that partition waits behind it</strong>.
 * One malformed message therefore stops a third of this service's work indefinitely, and the only
 * symptom is a consumer that has gone quiet. That is the poison-message problem, and it is the reason
 * a dead letter topic is not optional once a consumer does anything that can fail.
 *
 * <h2>The DLT is named per consumer group, not per topic</h2>
 *
 * <p>Spring's default is {@code <topic>.DLT}. Two services read {@code bookstore.order.placed}, so
 * the default would pour both services' failures into one topic — and then "how many notifications are
 * stuck?" cannot be answered without inspecting every record, and a replay tool would have to
 * re-deliver analytics failures to notification-service to find its own. The group name is what makes a
 * failure attributable, so it goes in the topic name.
 */
@Configuration
@Slf4j
public class KafkaErrorHandlingConfig {

    /**
     * Declared here because it is created by <em>this</em> service, unlike the topics it reads.
     * The broker has auto-creation disabled, and a DLT that does not exist turns a poison message into
     * a poison message plus a failing recoverer.
     */
    @Bean
    public NewTopic orderPlacedDeadLetterTopic(
            @Value("${app.kafka.topics.order-placed}") String topic,
            @Value("${app.kafka.groups.analytics}") String group) {

        return TopicBuilder.name(deadLetterNameFor(topic, group)).partitions(3).replicas(1).build();
    }

    /**
     * Retry a few times, then get out of the way.
     *
     * <p>Two failure modes, and they want opposite treatment:
     *
     * <ul>
     *   <li><strong>Transient</strong> — a database blip, a downstream timeout. Retrying works, and the
     *       backoff below gives it about seven seconds to.
     *   <li><strong>Deterministic</strong> — malformed JSON, a field that will never parse. Retrying is
     *       pure delay: the same bytes fail the same way forever, while the partition stalls. Those go
     *       straight to the DLT on the first attempt, via {@code addNotRetryableExceptions}.
     * </ul>
     *
     * <p>The retry budget is deliberately small. This is not the last line of defence — the DLT is —
     * and a long one converts "one bad message" into "this partition is minutes behind", which is the
     * same outage arriving more slowly.
     */
    @Bean
    public DefaultErrorHandler deadLetterErrorHandler(
            KafkaTemplate<Object, Object> template,
            @Value("${app.kafka.groups.analytics}") String group) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(
                        deadLetterNameFor(record.topic(), group), record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxAttempts(3);
        backOff.setMaxInterval(4000);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // A payload that cannot be turned into the expected type will not become one on the fourth
        // attempt. Straight to the DLT, and the partition keeps moving.
        handler.addNotRetryableExceptions(
                ConversionException.class,
                MessageConversionException.class,
                IllegalArgumentException.class);

        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Attempt {} failed for {}-{} offset {}: {}",
                        deliveryAttempt, record.topic(), record.partition(), record.offset(),
                        exception.getMessage()));

        return handler;
    }

    /**
     * {@code bookstore.order.placed} + {@code notification-service} ->
     * {@code bookstore.order.placed.notification-service.DLT}.
     *
     * <p>Kept as one method so the producing side and the monitoring side (analytics-service's
     * {@code DeadLetterMonitor}) cannot drift apart in the one way that would matter: a monitor
     * watching a topic name nobody writes to reports zero forever, which is indistinguishable from
     * healthy.
     */
    public static String deadLetterNameFor(String topic, String consumerGroup) {
        return topic + "." + consumerGroup + ".DLT";
    }
}
