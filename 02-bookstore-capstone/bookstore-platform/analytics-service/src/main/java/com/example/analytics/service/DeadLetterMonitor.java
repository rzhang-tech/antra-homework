package com.example.analytics.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Watches how much is stuck in the dead letter topics.
 *
 * <h2>Why a dead letter topic without a monitor is worse than none</h2>
 *
 * <p>The DLT's whole purpose is to get a failing message out of the way so the partition keeps moving.
 * That is also its danger: <strong>the symptom of failure is removed along with the failure.</strong>
 * Before the DLT, a poison message made a consumer visibly stop. After it, the consumer looks perfectly
 * healthy while orders quietly go unconfirmed, and nobody finds out until a customer asks. A dead letter
 * topic converts a loud failure into a silent one unless something is counting.
 *
 * <p>It lives in analytics-service because counting things is what this service is for, and because a
 * monitor inside the service that is failing is a monitor that can fail with it. In a real deployment
 * this is a Prometheus alert on consumer-group lag and DLT size, not a scheduled method — the honest
 * version of this class is "an alert with an owner", and the value here is knowing what to alert on.
 *
 * <h2>Depth, not lag</h2>
 *
 * <p>What is measured is the number of records in each DLT — end offset minus start offset — rather
 * than a consumer group's lag on it. Nothing consumes these topics; a human does, by reading them and
 * deciding whether to fix and replay or to discard. So the number that matters is "how many are in
 * there", and the alert-worthy threshold is <em>one</em>.
 */
@Component
@Slf4j
public class DeadLetterMonitor {

    private final AdminClient admin;
    private final MeterRegistry meters;
    private final List<String> deadLetterTopics;

    /** Held so Micrometer's gauges have something stable to read; gauges hold weak references. */
    private final Map<String, AtomicLong> depths = new ConcurrentHashMap<>();

    public DeadLetterMonitor(KafkaAdmin kafkaAdmin,
                             MeterRegistry meters,
                             @Value("${app.kafka.topics.order-placed}") String orderPlacedTopic,
                             @Value("${app.kafka.groups.analytics}") String analyticsGroup,
                             @Value("${app.kafka.groups.notification}") String notificationGroup,
                             @Value("${app.kafka.topics.payment-completed}") String paymentTopic) {

        this.admin = AdminClient.create(kafkaAdmin.getConfigurationProperties());
        this.meters = meters;

        // Every DLT on the platform, including the ones belonging to another service. A monitor that
        // only watched its own would leave notification-service's failures unobserved, which is
        // exactly the outcome this class exists to prevent.
        this.deadLetterTopics = List.of(
                com.example.analytics.config.KafkaErrorHandlingConfig
                        .deadLetterNameFor(orderPlacedTopic, analyticsGroup),
                com.example.analytics.config.KafkaErrorHandlingConfig
                        .deadLetterNameFor(orderPlacedTopic, notificationGroup),
                com.example.analytics.config.KafkaErrorHandlingConfig
                        .deadLetterNameFor(paymentTopic, notificationGroup));
    }

    /**
     * Reports depth, and says nothing at all when everything is empty.
     *
     * <p>A monitor that logs "0 messages in the DLT" every thirty seconds trains everyone to skip its
     * output, which is how the one line that mattered gets missed. Silence is the healthy state; WARN
     * is the only volume worth using for a number that should be zero.
     */
    @Scheduled(fixedDelayString = "${app.kafka.dlq-check-interval-ms}")
    public void reportDepth() {
        for (String topic : deadLetterTopics) {
            try {
                long depth = depthOf(topic);
                gaugeFor(topic).set(depth);

                if (depth > 0) {
                    log.warn("DEAD LETTER: {} holds {} message(s) nothing has dealt with. "
                                    + "Read them, fix the cause, then replay or discard deliberately.",
                            topic, depth);
                }
            } catch (Exception ex) {
                // Including "topic does not exist yet", which is normal before the first failure.
                log.debug("Could not read depth of {}: {}", topic, ex.toString());
            }
        }
    }

    private long depthOf(String topic) throws Exception {
        var description = admin.describeTopics(List.of(topic)).allTopicNames().get().get(topic);

        Map<TopicPartition, OffsetSpec> earliest = new HashMap<>();
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        description.partitions().forEach(p -> {
            TopicPartition tp = new TopicPartition(topic, p.partition());
            earliest.put(tp, OffsetSpec.earliest());
            latest.put(tp, OffsetSpec.latest());
        });

        ListOffsetsResult starts = admin.listOffsets(earliest);
        ListOffsetsResult ends = admin.listOffsets(latest);

        long depth = 0;
        for (TopicPartition tp : latest.keySet()) {
            // End minus start, not just end: retention deletes old records and their offsets do not
            // come back, so `end` alone would keep reporting messages that no longer exist.
            depth += ends.partitionResult(tp).get().offset() - starts.partitionResult(tp).get().offset();
        }
        return depth;
    }

    private AtomicLong gaugeFor(String topic) {
        return depths.computeIfAbsent(topic, name -> {
            AtomicLong holder = new AtomicLong();
            meters.gauge("bookstore.dlq.depth", List.of(io.micrometer.core.instrument.Tag.of("topic", name)), holder);
            return holder;
        });
    }
}
