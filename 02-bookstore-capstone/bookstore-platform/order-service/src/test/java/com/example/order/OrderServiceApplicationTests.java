package com.example.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: the context starts, every bean wires, and Flyway's schema matches the entities.
 *
 * <p>{@code app.book-service.url} is set to an address nothing listens on: this test starts the
 * application, it does not call the catalog. A context that needed a live book-service to start would
 * mean order-service could not boot during a catalog outage — the opposite of independent deployability.
 */
@SpringBootTest(properties = "app.book-service.url=http://localhost:59999")
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@DisplayName("Application context")
class OrderServiceApplicationTests {

    @Test
    @DisplayName("starts without book-service being reachable")
    void contextLoads() {
    }
}
