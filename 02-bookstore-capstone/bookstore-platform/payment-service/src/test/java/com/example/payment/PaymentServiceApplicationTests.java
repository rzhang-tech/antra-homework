package com.example.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: the context starts and Flyway's schema matches the entities.
 *
 * <p>order-service is pointed at an address nothing listens on, deliberately. payment-service must be
 * able to boot during an order-service outage — a service that cannot start without its dependencies
 * is not independently deployable, and turns one restart into a cascade.
 */
@SpringBootTest(properties = "app.order-service.url=http://localhost:59998")
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@DisplayName("Application context")
class PaymentServiceApplicationTests {

    @Test
    @DisplayName("starts without order-service being reachable")
    void contextLoads() {
    }
}
