package com.example.bookstore.integration;

import com.example.bookstore.TestcontainersConfig;
import com.example.bookstore.dto.BookResponseDto;
import com.example.bookstore.dto.LoginResponseDto;
import com.example.bookstore.dto.UserResponseDto;
import com.example.bookstore.entity.Book;
import com.example.bookstore.entity.Role;
import com.example.bookstore.entity.User;
import com.example.bookstore.repository.BookRepository;
import com.example.bookstore.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full path: real HTTP over a real servlet container, through the real security filter chain, into
 * the real services, against a real PostgreSQL.
 *
 * <p>Nothing is mocked here — which is exactly what makes it worth having. The unit tests mock the
 * repository, the web slice mocks the service; each proves its layer in isolation and neither proves
 * that the layers fit together. A token issued by the login endpoint being accepted by the purchase
 * endpoint, and the resulting stock change actually reaching the database, can only be established by
 * doing all of it.
 *
 * <p>The cost is speed: a full context, a container, and a servlet port. So there is one class of these,
 * covering the paths that matter, rather than a suite mirroring the unit tests.
 *
 * <p>Named for purchase rather than orders — the assignment's {@code OrderFlowIntegrationTest} covers an
 * order flow that does not exist until Step 5. Purchase is the same shape: authenticate, mutate stock
 * transactionally, observe the result.
 *
 * <p>Note that {@code @SpringBootTest} with a real port does <em>not</em> roll back: the server handles
 * each request on its own thread in its own transaction. Every test therefore creates its own data with
 * a unique ISBN rather than relying on a clean slate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@DisplayName("Purchase flow (end to end)")
class PurchaseFlowIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private BookRepository bookRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String ADMIN_PASSWORD = "admin-integration-password";

    private Long bookId;

    @BeforeEach
    void createFixtures() {
        Book book = bookRepository.save(Book.builder()
                .title("Integration Test Book")
                .isbn("IT-" + System.nanoTime())
                .price(new BigDecimal("19.99"))
                .stock(20)
                .build());
        bookId = book.getId();

        // The test profile does not load db/seed, so the dev admin does not exist here. Creating one
        // directly is the only route: registration deliberately cannot produce an ADMIN.
        if (userRepository.findByUsername("integration-admin").isEmpty()) {
            userRepository.save(User.builder()
                    .username("integration-admin")
                    .email("integration-admin@example.com")
                    .passwordHash(passwordEncoder.encode(ADMIN_PASSWORD))
                    .role(Role.ADMIN)
                    .build());
        }
    }

    // ---------------------------------------------------------------- helpers

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = json();
        headers.setBearerAuth(token);
        return headers;
    }

    private String loginAs(String username, String password) {
        ResponseEntity<LoginResponseDto> response = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), json()),
                LoginResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().token();
    }

    private ResponseEntity<String> purchase(String token, int quantity) {
        HttpHeaders headers = token == null ? json() : bearer(token);
        return rest.exchange("/api/books/" + bookId + "/purchase", HttpMethod.POST,
                new HttpEntity<>(Map.of("quantity", quantity), headers), String.class);
    }

    private int stockInDatabase() {
        return bookRepository.findById(bookId).orElseThrow().getStock();
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("register -> login -> browse -> purchase, with the stock change reaching the database")
    void fullCustomerJourney() {
        String username = "customer-" + System.nanoTime();

        // 1. Register. Nothing in the response resembles the password.
        ResponseEntity<UserResponseDto> registered = rest.postForEntity("/api/auth/register",
                new HttpEntity<>(Map.of(
                        "username", username,
                        "email", username + "@example.com",
                        "password", "correct-horse-battery"), json()),
                UserResponseDto.class);

        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody().role()).isEqualTo(Role.USER);

        // 2. Browsing needs no credentials at all.
        assertThat(rest.getForEntity("/api/books/" + bookId, BookResponseDto.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 3. Log in and receive a token.
        String token = loginAs(username, "correct-horse-battery");
        assertThat(token).isNotBlank();

        // 4. The token issued by one endpoint is accepted by another — the join the slice tests cannot
        //    make, because each of them stubs the other side.
        ResponseEntity<UserResponseDto> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(token)), UserResponseDto.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().username()).isEqualTo(username);

        // 5. Purchase.
        assertThat(purchase(token, 3).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 6. And the decrement is really in PostgreSQL, not merely in the response body.
        assertThat(stockInDatabase()).isEqualTo(17);
    }

    @Test
    @DisplayName("purchasing without a token is rejected and leaves stock untouched")
    void anonymousPurchaseChangesNothing() {
        ResponseEntity<String> response = purchase(null, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(stockInDatabase()).isEqualTo(20);
    }

    @Test
    @DisplayName("a tampered token is rejected all the way through")
    void tamperedTokenIsRejected() {
        String username = "tamperer-" + System.nanoTime();
        rest.postForEntity("/api/auth/register", new HttpEntity<>(Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "correct-horse-battery"), json()), UserResponseDto.class);

        String token = loginAs(username, "correct-horse-battery");

        // Rewrite the payload to claim ADMIN, leaving the signature alone — the attack the whole
        // signature scheme exists to stop.
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        payload.replace("\"USER\"", "\"ADMIN\"").getBytes())
                + "." + parts[2];

        ResponseEntity<String> response = rest.exchange("/api/books", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "title", "Forged", "isbn", "FORGE-1",
                        "price", 9.99, "stock", 1), bearer(forged)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a customer gets 403 on an admin route; the admin gets 201")
    void roleRulesHoldOverRealHttp() {
        String username = "shopper-" + System.nanoTime();
        rest.postForEntity("/api/auth/register", new HttpEntity<>(Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "correct-horse-battery"), json()), UserResponseDto.class);

        HttpEntity<Map<String, Object>> body = new HttpEntity<>(Map.of(
                "title", "Staff Only", "isbn", "SO-" + System.nanoTime(),
                "price", 9.99, "stock", 1), bearer(loginAs(username, "correct-horse-battery")));

        assertThat(rest.exchange("/api/books", HttpMethod.POST, body, String.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        HttpEntity<Map<String, Object>> asAdmin = new HttpEntity<>(Map.of(
                "title", "Staff Only", "isbn", "SO-" + System.nanoTime(),
                "price", 9.99, "stock", 1),
                bearer(loginAs("integration-admin", ADMIN_PASSWORD)));

        assertThat(rest.exchange("/api/books", HttpMethod.POST, asAdmin, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("buying more than the shelf holds is a 409 and changes nothing")
    void overbuyingIsRejected() {
        String username = "greedy-" + System.nanoTime();
        rest.postForEntity("/api/auth/register", new HttpEntity<>(Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "correct-horse-battery"), json()), UserResponseDto.class);

        ResponseEntity<String> response = purchase(loginAs(username, "correct-horse-battery"), 21);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("only 20");
        assertThat(stockInDatabase()).isEqualTo(20);
    }

    @Test
    @DisplayName("concurrent purchases never oversell — optimistic locking, in CI rather than by hand")
    void concurrentPurchasesNeverOversell() throws Exception {
        String username = "racer-" + System.nanoTime();
        rest.postForEntity("/api/auth/register", new HttpEntity<>(Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", "correct-horse-battery"), json()), UserResponseDto.class);
        String token = loginAs(username, "correct-horse-battery");

        int attempts = 20;
        List<Integer> statuses;
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Callable<Integer>> calls = IntStream.range(0, attempts)
                    .<Callable<Integer>>mapToObj(i -> () -> purchase(token, 1).getStatusCode().value())
                    .toList();
            statuses = pool.invokeAll(calls).stream().map(PurchaseFlowIntegrationTest::get).toList();
        }

        long succeeded = statuses.stream().filter(s -> s == 200).count();
        long conflicted = statuses.stream().filter(s -> s == 409).count();

        // How many conflict depends on timing and is not asserted. What must hold regardless:
        assertThat(succeeded + conflicted).isEqualTo(attempts);   // no other outcome, in particular no 500
        assertThat(stockInDatabase()).isEqualTo((int) (20 - succeeded));  // no lost update
        assertThat(stockInDatabase()).isNotNegative();            // never oversold
    }

    private static Integer get(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
