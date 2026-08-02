package com.example.order.client;

import com.example.order.TestcontainersConfig;
import com.example.order.exception.CatalogUnavailableException;
import com.example.order.exception.OrderNotAllowedException;
import com.example.order.exception.ResourceNotFoundException;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the resilience policy behaves, against a fake book-service that can be told to fail on demand.
 *
 * <p>WireMock is a real HTTP server, so these tests exercise the whole path — Feign, the error decoder,
 * Retry, and the circuit breaker — rather than a mock's opinion of it. That matters here more than
 * usual: the interesting behaviour lives in annotations and YAML, which a unit test cannot reach at all.
 *
 * <p>The service and repository layers are not involved; only the gateway and its configuration.
 */
@SpringBootTest(properties = {
        // Shorter than production's 2s/3s so the timeout test does not dominate the suite's runtime.
        // The behaviour under test is "does it give up in bounded time", not the specific bound.
        "spring.cloud.openfeign.client.config.default.connect-timeout=500",
        "spring.cloud.openfeign.client.config.default.read-timeout=1000"
})
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@DisplayName("CatalogGateway resilience")
class CatalogGatewayResilienceTest {

    private static WireMockServer catalogStub;

    @Autowired private CatalogGateway gateway;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeAll
    static void startStub() {
        catalogStub = new WireMockServer(0);   // 0 = any free port
        catalogStub.start();
    }

    @AfterAll
    static void stopStub() {
        catalogStub.stop();
    }

    @DynamicPropertySource
    static void pointAtStub(DynamicPropertyRegistry registry) {
        registry.add("app.book-service.url", () -> "http://localhost:" + catalogStub.port());
    }

    @BeforeEach
    void reset() {
        catalogStub.resetAll();
        // Each test starts from CLOSED — otherwise one test's failures decide the next test's outcome,
        // and the suite passes or fails depending on the order JUnit happens to pick.
        circuitBreakerRegistry.circuitBreaker(CatalogGateway.CATALOG).reset();
    }

    private CircuitBreaker.State state() {
        return circuitBreakerRegistry.circuitBreaker(CatalogGateway.CATALOG).getState();
    }

    private static final String BOOK_JSON = """
            {"id":1,"title":"Clean Code","price":42.50,"stock":10}""";

    @Test
    @DisplayName("a healthy call succeeds and leaves the circuit closed")
    void happyPath() {
        catalogStub.stubFor(get(urlEqualTo("/api/books/1")).willReturn(okJson(BOOK_JSON)));

        assertThat(gateway.findById(1L).title()).isEqualTo("Clean Code");
        assertThat(state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a read is retried three times before giving up")
    void readIsRetried() {
        catalogStub.stubFor(get(urlEqualTo("/api/books/1"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> gateway.findById(1L))
                .isInstanceOf(CatalogUnavailableException.class);

        // max-attempts: 3 means three requests actually reached the server — a GET is idempotent, so
        // repeating it is free.
        catalogStub.verify(3, getRequestedFor(urlEqualTo("/api/books/1")));
    }

    @Test
    @DisplayName("a stock reservation is NEVER retried — a repeat would sell the book twice")
    void writeIsNotRetried() {
        catalogStub.stubFor(post(urlPathEqualTo("/api/books/1/purchase"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> gateway.purchase(1L, 1))
                .isInstanceOf(CatalogUnavailableException.class);

        // Exactly one. The dangerous case is book-service committing the decrement and the response
        // being lost on the way back — indistinguishable from "nothing happened", and retried it takes
        // a second copy off the shelf.
        catalogStub.verify(1, postRequestedFor(urlPathEqualTo("/api/books/1/purchase")));
    }

    @Test
    @DisplayName("repeated failures open the circuit, and it then fails instantly without calling out")
    void circuitOpensAndFailsFast() {
        catalogStub.stubFor(get(urlPathEqualTo("/api/books/1"))
                .willReturn(aResponse().withStatus(500)));

        // minimum-number-of-calls is 5, and each call is 3 retried attempts; two calls is enough to
        // pass the threshold once retries are counted as one failure each.
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> gateway.findById(1L))
                    .isInstanceOf(CatalogUnavailableException.class);
        }

        assertThat(state()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestsSoFar = catalogStub.getServeEvents().getRequests().size();

        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway.findById(1L))
                .isInstanceOf(CatalogUnavailableException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // The point of the circuit breaker, in two assertions: no request left the process...
        assertThat(catalogStub.getServeEvents().getRequests()).hasSize(requestsSoFar);
        // ...and the caller found out immediately rather than waiting out a timeout.
        assertThat(elapsedMillis).isLessThan(100);
    }

    @Test
    @DisplayName("a 404 is a valid answer: not retried, and it does not open the circuit")
    void notFoundDoesNotCountAsFailure() {
        catalogStub.stubFor(get(urlEqualTo("/api/books/404"))
                .willReturn(aResponse().withStatus(404)));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> gateway.findById(404L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        // Ten "no such book" answers are ten healthy responses. A breaker that counted them would open
        // under ordinary traffic and take the catalog down because customers mistyped an id.
        assertThat(state()).isEqualTo(CircuitBreaker.State.CLOSED);
        catalogStub.verify(10, getRequestedFor(urlEqualTo("/api/books/404")));
    }

    @Test
    @DisplayName("a 409 is likewise a business answer, not a failure")
    void conflictDoesNotCountAsFailure() {
        catalogStub.stubFor(post(urlPathEqualTo("/api/books/1/purchase"))
                .willReturn(aResponse().withStatus(409)));

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> gateway.purchase(1L, 1))
                    .isInstanceOf(OrderNotAllowedException.class);
        }

        assertThat(state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a transient failure is absorbed: attempt one fails, attempt two succeeds")
    void transientFailureIsAbsorbedByRetry() {
        catalogStub.stubFor(get(urlEqualTo("/api/books/1"))
                .inScenario("flaky").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        catalogStub.stubFor(get(urlEqualTo("/api/books/1"))
                .inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson(BOOK_JSON)));

        // The caller never learns anything went wrong, which is exactly what retry is for.
        assertThat(gateway.findById(1L).title()).isEqualTo("Clean Code");
        assertThat(state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("a call slower than the read timeout fails rather than hanging")
    void slowCallTimesOut() {
        catalogStub.stubFor(get(urlEqualTo("/api/books/1"))
                .willReturn(okJson(BOOK_JSON).withFixedDelay(3000)));   // read-timeout is 1000ms here

        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway.findById(1L))
                .isInstanceOf(CatalogUnavailableException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // Three attempts of ~1s each plus backoff — bounded, and nothing like waiting forever, which is
        // what Feign does without an explicit timeout.
        assertThat(elapsedMillis).isLessThan(6000);
    }
}
