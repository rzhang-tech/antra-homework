package com.example.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * What the gateway does with a route table.
 *
 * <p>A real backend rather than a mocked one, for the same reason 5c used WireMock: the interesting
 * behaviour is in configuration and in a proxy, and neither can be exercised by stubbing a Java
 * interface. What arrives at the far end — the path, the query string, the headers — is the contract,
 * and only an HTTP server can report it.
 *
 * <p>These tests use a <em>test</em> route table (see {@code application-test.yml}) because the real
 * one lives in the config repo. That split is deliberate and its limits are stated there: this class
 * tests Gateway's behaviour, and {@code ConfigServerContractTest} tests that the real table names the
 * right services.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("Routing")
class RoutingTest {

    private static WireMockServer backend;

    @Autowired
    private WebTestClient client;

    @BeforeAll
    static void startBackend() {
        backend = new WireMockServer(0);   // 0 = any free port
        backend.start();
    }

    @AfterAll
    static void stopBackend() {
        backend.stop();
    }

    @DynamicPropertySource
    static void backendAddress(DynamicPropertyRegistry registry) {
        registry.add("test.backend.url", () -> "http://localhost:" + backend.port());
    }

    @BeforeEach
    void resetBackend() {
        backend.resetAll();
    }

    @Test
    @DisplayName("forwards the path unchanged - the gateway is a front door, not a translator")
    void forwardsThePathAsReceived() {
        backend.stubFor(get(urlEqualTo("/api/books/42")).willReturn(okJson("{\"id\":42}")));

        client.get().uri("/api/books/42")
                .exchange()
                .expectStatus().isOk();

        // No StripPrefix, no RewritePath. A client's URL and a service's URL being the same string is
        // worth protecting: it means a stack trace, a log line and a curl command all refer to the
        // same thing, and it means moving a service behind the gateway changed nothing for callers.
        backend.verify(getRequestedFor(urlEqualTo("/api/books/42")));
    }

    @Test
    @DisplayName("passes the Authorization header through untouched")
    void forwardsTheCallersToken() {
        backend.stubFor(get(urlPathEqualTo("/api/orders")).willReturn(okJson("[]")));

        // A genuine token, since Step 8b. This test used to send `Bearer a.b.c` and get 200 - the
        // moment the edge filter appeared it started failing, which is the filter demonstrating it is
        // actually in the request path rather than merely configured.
        String token = TestTokens.customer();

        client.get().uri("/api/orders")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        // Step 8b validates this token at the edge and then forwards it anyway, rather than replacing
        // it with a trusted header. This assertion is what stops that decision being undone by
        // accident: strip the token here and every downstream authorization rule starts seeing an
        // anonymous request.
        backend.verify(getRequestedFor(urlPathEqualTo("/api/orders"))
                .withHeader("Authorization", equalTo("Bearer " + token)));
    }

    @Test
    @DisplayName("preserves the query string, which is where paging lives")
    void forwardsQueryParameters() {
        backend.stubFor(get(urlEqualTo("/api/books?page=2&size=5")).willReturn(okJson("{}")));

        client.get().uri("/api/books?page=2&size=5")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    @DisplayName("a path no route claims is refused at the edge, not forwarded to somebody")
    void unmatchedPathsNeverReachABackend() {
        client.get().uri("/api/nothing-owns-this")
                .exchange()
                .expectStatus().isNotFound();

        // The failure mode this prevents: a catch-all route, or a route whose predicate is broader
        // than intended, quietly delivering /actuator/env to a service. Nothing routes what no
        // predicate matches, and that default is the reason /actuator/** is unreachable from outside
        // without a single rule saying so.
        backend.verify(0, getRequestedFor(urlPathEqualTo("/api/nothing-owns-this")));
    }

    @Test
    @DisplayName("the backend's status code is the client's status code")
    void doesNotReinterpretDownstreamFailures() {
        backend.stubFor(get(urlPathEqualTo("/api/orders/999"))
                .willReturn(aResponse().withStatus(409).withBody("{\"error\":\"Conflict\"}")));

        client.get().uri("/api/orders/999")
                .header("Authorization", "Bearer " + TestTokens.customer())
                .exchange()
                .expectStatus().isEqualTo(409);

        // A gateway that normalised statuses would erase the difference between "the catalog is down"
        // (503, retry later) and "you cannot have that" (409, never retry) - a distinction Steps 5b
        // and 5c spent considerable effort making true.
    }
}
