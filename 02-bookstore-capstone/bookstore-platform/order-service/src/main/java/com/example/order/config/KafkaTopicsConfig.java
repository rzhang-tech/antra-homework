package com.example.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topics this service owns.
 *
 * <p>Explicit, because the broker runs with {@code auto.create.topics.enable=false}. Auto-creation is
 * convenient exactly once and then costs you: a typo in a topic name silently becomes a brand new topic
 * that nothing publishes to and nothing reads, and the symptom is a consumer that is simply quiet. It
 * also creates topics with whatever the broker defaults happen to be, which is how a topic ends up with
 * one partition and no ordering guarantees anybody chose.
 *
 * <p><strong>The producer declares the topic, not the consumers.</strong> A topic belongs to whoever
 * publishes to it, the same way a table belongs to the service that writes it. Consumers come and go —
 * two of them by the end of Step 7 — and none of them should be able to change its shape.
 */
@Configuration
public class KafkaTopicsConfig {

    /**
     * Three partitions, so that keying by order id actually decides something.
     *
     * <p>With one partition every message is ordered relative to every other and the key is decoration;
     * with three, events for one order still arrive in order while unrelated orders are processed in
     * parallel — which is the property the key buys and the reason to key at all.
     *
     * <p>Partition count is close to permanent: raising it later re-hashes keys onto different
     * partitions, so a key's history is split across two of them and per-key ordering is broken for
     * everything already written. It bounds consumer parallelism too — a consumer group can never have
     * more useful members than the topic has partitions.
     */
    @Bean
    public NewTopic orderPlacedTopic(@Value("${app.kafka.topics.order-placed}") String name) {
        return TopicBuilder.name(name)
                .partitions(3)
                .replicas(1)   // one broker locally; a real cluster wants 3 with min.insync.replicas=2
                .build();
    }
}
