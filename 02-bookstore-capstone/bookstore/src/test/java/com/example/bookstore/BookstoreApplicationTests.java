package com.example.bookstore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the Spring context starts. Catches wiring mistakes — a missing bean, a bad property, an
 * unresolvable placeholder — before they show up as a failed startup.
 *
 * <p>The real test suite (unit, web slice, repository slice, integration) is built in Step 4.
 */
@SpringBootTest
class BookstoreApplicationTests {

    @Test
    void contextLoads() {
    }
}
