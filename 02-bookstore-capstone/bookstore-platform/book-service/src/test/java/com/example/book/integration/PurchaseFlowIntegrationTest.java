package com.example.book.integration;

import com.example.book.TestcontainersConfig;
import com.example.book.config.JwtProperties;
import com.example.book.entity.Book;
import com.example.book.repository.BookRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * book-service end to end: real HTTP, real filter chain, real PostgreSQL.
 *
 * <p><strong>What changed when the monolith split.</strong> This test used to begin by calling
 * {@code /api/auth/register} and {@code /api/auth/login}. Those endpoints now live in a different
 * service, in a different process, with a different database — so a test of book-service cannot call
 * them without becoming a test of the whole platform, failing whenever the other service is down.
 *
 * <p>Instead it mints its own token with the shared signing key. That is not a workaround; it is the
 * design being exercised directly. book-service never verified tokens by asking user-service — it
 * verifies a signature. Anything holding the key can produce a token it accepts, and that is precisely
 * the contract under test.
 *
 * <p>The real login-then-call path is not untested, it is tested at a different level: the manual
 * checks in {@code test-platform.http}, and Step 8's gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@DisplayName("Purchase flow (book-service, end to end)")
class PurchaseFlowIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private BookRepository bookRepository;
    @Autowired private JwtProperties jwtProperties;

    private Long bookId;

    @BeforeEach
    void createFixture() {
        bookId = bookRepository.save(Book.builder()
                .title("Integration Test Book")
                .isbn("IT-" + System.nanoTime())
                .price(new BigDecimal("19.99"))
                .stock(20)
                .version(0L)
                .build()).getId();
    }

    /** Produces exactly what user-service would, using the key both services share. */
    private String tokenFor(String username, String role) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuer(jwtProperties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .signWith(key)
                .compact();
    }

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

    private ResponseEntity<String> purchase(String token, int quantity) {
        HttpHeaders headers = token == null ? json() : bearer(token);
        return rest.exchange("/api/books/" + bookId + "/purchase", HttpMethod.POST,
                new HttpEntity<>(Map.of("quantity", quantity), headers), String.class);
    }

    private ResponseEntity<String> createBook(String token) {
        return rest.exchange("/api/books", HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "title", "Staff Only", "isbn", "SO-" + System.nanoTime(),
                        "price", 9.99, "stock", 1), bearer(token)), String.class);
    }

    private int stockInDatabase() {
        return bookRepository.findById(bookId).orElseThrow().getStock();
    }

    @Test
    @DisplayName("browse anonymously, then purchase with a token — the stock change reaches the database")
    void browseThenPurchase() {
        assertThat(rest.getForEntity("/api/books/" + bookId, String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(purchase(tokenFor("shopper", "USER"), 3).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(stockInDatabase()).isEqualTo(17);
    }

    @Test
    @DisplayName("purchasing without a token is rejected and leaves stock untouched")
    void anonymousPurchaseChangesNothing() {
        assertThat(purchase(null, 1).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(stockInDatabase()).isEqualTo(20);
    }

    @Test
    @DisplayName("a token signed with a different key is rejected — this is the whole trust model")
    void tokenSignedWithAnotherKeyIsRejected() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "an-entirely-different-signing-key-of-sufficient-length".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .subject("intruder").claim("role", "ADMIN").issuer(jwtProperties.issuer())
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(wrongKey).compact();

        assertThat(purchase(forged, 1).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(stockInDatabase()).isEqualTo(20);
    }

    @Test
    @DisplayName("a genuine token with its role claim rewritten is rejected")
    void tamperedRoleIsRejected() {
        String[] parts = tokenFor("shopper", "USER").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                        payload.replace("\"USER\"", "\"ADMIN\"").getBytes())
                + "." + parts[2];

        assertThat(createBook(forged).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an unknown role matches no rule and is refused rather than crashing the filter")
    void unknownRoleIsRefusedNotFatal() {
        // user-service could add a role tomorrow without telling this service. It must degrade to 403,
        // not to a 500 out of Role.valueOf — which is why JwtUtil.roleOf returns a String.
        assertThat(createBook(tokenFor("manager", "MANAGER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("a customer gets 403 on an admin route; an admin gets 201")
    void roleRulesHoldOverRealHttp() {
        assertThat(createBook(tokenFor("shopper", "USER")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(createBook(tokenFor("boss", "ADMIN")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("buying more than the shelf holds is a 409 and changes nothing")
    void overbuyingIsRejected() {
        ResponseEntity<String> response = purchase(tokenFor("greedy", "USER"), 21);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("only 20");
        assertThat(stockInDatabase()).isEqualTo(20);
    }

    @Test
    @DisplayName("concurrent purchases never oversell — optimistic locking, in CI rather than by hand")
    void concurrentPurchasesNeverOversell() throws Exception {
        String token = tokenFor("racer", "USER");
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
        assertThat(succeeded + conflicted).isEqualTo(attempts);           // no other outcome, no 500
        assertThat(stockInDatabase()).isEqualTo((int) (20 - succeeded));  // no lost update
        assertThat(stockInDatabase()).isNotNegative();                    // never oversold
    }

    private static Integer get(Future<Integer> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
