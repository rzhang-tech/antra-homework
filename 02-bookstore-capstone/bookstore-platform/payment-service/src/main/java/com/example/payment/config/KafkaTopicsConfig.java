package com.example.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic this service owns.
 *
 * <p>A topic belongs to whoever publishes to it, the same way a table belongs to the service that
 * writes it. notification-service reads this one and does not declare it — a consumer that created its
 * own topics could quietly create the wrong one after a rename and then wait forever on it.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public NewTopic paymentCompletedTopic(@Value("${app.kafka.topics.payment-completed}") String name) {
        return TopicBuilder.name(name)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
