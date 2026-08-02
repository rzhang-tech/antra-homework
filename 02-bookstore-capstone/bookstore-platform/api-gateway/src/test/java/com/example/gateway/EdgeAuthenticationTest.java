package com.example.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * What the edge lets through, and what it refuses to be told.
 *
 * <p>Every assertion below is about one of two questions: does a request reach a service at all, and
 * what does it look like when it does. WireMock answers both — the second one cannot be asked of a
 * mock, because the interesting part is the bytes on the wire.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("The edge")
class EdgeAuthenticationTest {

    private static WireMockServer backend;

    @Autowired
    private WebTestClient client;

    @BeforeAll
    static void startBackend() {
        backend = new WireMockServer(0);
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
        backend.stubFor(get(urlPathEqualTo("/api/orders")).willReturn(okJson("[]")));
        backend.stubFor(get(urlPathEqualTo("/api/books")).willReturn(okJson("[]")));
    }

    @Nested
    @DisplayName("refusing")
    class Refusing {

        @Test
        @DisplayName("a protected route without a token never reaches a service")
        void noTokenIsRefusedBeforeRouting() {
            client.get().uri("/api/orders")
                    .exchange()
                    .expectStatus().isUnauthorized();

            // The assertion that matters is the second one. Returning 401 is easy; returning it
            // WITHOUT spending a connection, a thread and a database session downstream is what an
            // edge is for, and it is the difference between a credential-stuffing attempt being an
            // inconvenience and being an outage.
            backend.verify(0, getRequestedFor(urlPathEqualTo("/api/orders")));
        }

        @Test
        @DisplayName("a forged token never reaches a service either")
        void invalidTokenIsRefusedBeforeRouting() {
            client.get().uri("/api/orders")
                    .header("Authorization", "Bearer not.a.real.token")
                    .exchange()
                    .expectStatus().isUnauthorized();

            backend.verify(0, getRequestedFor(urlPathEqualTo("/api/orders")));
        }

        @Test
        @DisplayName("an expired token is refused, however genuine its signature")
        void expiredTokenIsRefused() {
            client.get().uri("/api/orders")
                    .header("Authorization", "Bearer " + TestTokens.forUser("shopper", 7L, "USER", Duration.ofMinutes(-1)))
                    .exchange()
                    .expectStatus().isUnauthorized();

            backend.verify(0, getRequestedFor(urlPathEqualTo("/api/orders")));
        }
    }

    @Nested
    @DisplayName("letting through")
    class LettingThrough {

        @Test
        @DisplayName("browsing the catalogue needs no token - you can shop before you register")
        void publicRoutesDoNotRequireAToken() {
            client.get().uri("/api/books")
                    .exchange()
                    .expectStatus().isOk();

            backend.verify(getRequestedFor(urlPathEqualTo("/api/books")));
        }

        @Test
        @DisplayName("a valid token is forwarded UNTOUCHED, alongside the verified identity")
        void forwardsTheTokenAndTheIdentity() {
            // Minted ONCE. Calling TestTokens.customer() again for the assertion would usually produce
            // an identical string - JWT `iat` and `exp` are epoch SECONDS, so two tokens made in the
            // same second are byte-identical - and would differ whenever the second happened to tick
            // between the two calls. A test that passes 99 times in 100 is worse than one that fails.
            String token = TestTokens.customer();

            client.get().uri("/api/orders")
                    .header("Authorization", "Bearer " + token)
                    .exchange()
                    .expectStatus().isOk();

            // Both, and the first one is the important half. The services verify the SIGNATURE, not
            // these headers - so stripping the token here and forwarding only X-Auth-* would make
            // every downstream request anonymous, and would make the platform's whole authorization
            // model rest on network topology that nothing enforces.
            backend.verify(getRequestedFor(urlPathEqualTo("/api/orders"))
                    .withHeader("Authorization", equalTo("Bearer " + token))
                    .withHeader("X-Auth-User", equalTo("shopper"))
                    .withHeader("X-Auth-User-Id", equalTo("7"))
                    .withHeader("X-Auth-Role", equalTo("USER")));
        }
    }

    @Nested
    @DisplayName("refusing to be told who the caller is")
    class RefusingForgedIdentity {

        @Test
        @DisplayName("client-supplied identity headers are stripped, not forwarded")
        void aClientCannotDeclareItselfAdmin() {
            // THE test. The moment any downstream code reads X-Auth-Role - and "the gateway always
            // sets it" is exactly the reasoning that leads there - this curl becomes a complete
            // authentication bypass unless the header is cleared on the way in.
            client.get().uri("/api/orders")
                    .header("X-Auth-User", "admin")
                    .header("X-Auth-User-Id", "1")
                    .header("X-Auth-Role", "ADMIN")
                    .exchange()
                    .expectStatus().isUnauthorized();

            backend.verify(0, getRequestedFor(urlPathEqualTo("/api/orders")));
        }

        @Test
        @DisplayName("a real customer cannot upgrade themselves by adding a header")
        void headersAreReplacedByTheVerifiedIdentityNotMergedWithIt() {
            client.get().uri("/api/orders")
                    .header("Authorization", "Bearer " + TestTokens.customer())
                    .header("X-Auth-Role", "ADMIN")
                    .header("X-Auth-User-Id", "1")
                    .exchange()
                    .expectStatus().isOk();

            // The request IS allowed through - the token is genuine - but it arrives describing the
            // person the token says it is, not the person the caller claimed. A filter that added its
            // headers without first removing the client's would produce two X-Auth-Role values, and
            // which one a downstream reader picks is the kind of thing that differs between HTTP
            // libraries.
            backend.verify(getRequestedFor(urlPathEqualTo("/api/orders"))
                    .withHeader("X-Auth-Role", equalTo("USER"))
                    .withHeader("X-Auth-User-Id", equalTo("7")));
        }

        @Test
        @DisplayName("stripping happens on public routes too, where nothing else checks")
        void publicRoutesAreSanitisedAsWell() {
            client.get().uri("/api/books")
                    .header("X-Auth-Role", "ADMIN")
                    .exchange()
                    .expectStatus().isOk();

            // A public route is the one door with no guard behind it. An unsanitised X-Auth-Role
            // arriving there would be the same hole, entered by the one path nobody thinks to check.
            backend.verify(getRequestedFor(urlPathEqualTo("/api/books"))
                    .withHeader("X-Auth-Role", absent()));
        }
    }

}
