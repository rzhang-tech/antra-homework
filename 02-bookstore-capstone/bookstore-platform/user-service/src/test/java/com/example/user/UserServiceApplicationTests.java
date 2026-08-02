package com.example.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test: the whole application context starts.
 *
 * <p>Catches wiring mistakes — a missing bean, an unresolvable placeholder, an entity that no longer
 * matches the schema — before they appear as a failed deployment. With {@code ddl-auto: validate}, a
 * migration and an entity drifting apart fails right here.
 *
 * <p>Since Step 4b this brings its own PostgreSQL. Before, it pointed at {@code localhost:5432} through
 * the dev profile, so {@code ./mvnw test} failed unless someone had run {@code docker compose up -d}
 * first — an instruction that works for a person at a keyboard and not at all for CI.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@DisplayName("Application context")
class UserServiceApplicationTests {

    @Test
    @DisplayName("starts, with every bean wired and the schema validated against the entities")
    void contextLoads() {
    }
}
