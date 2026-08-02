package com.example.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * CORS, and the one interaction between it and edge authentication that has to be right.
 *
 * <p>A browser sends {@code OPTIONS} with <strong>no {@code Authorization} header at all</strong>
 * before any request it considers non-simple. If the auth filter answered that preflight with 401,
 * the browser would never send the real request — and the developer would see a CORS error in the
 * console with nothing anywhere mentioning a token. That is a filter <em>ordering</em> property, so it
 * cannot be verified by reading the YAML; it needs a request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DisplayName("CORS")
class CorsTest {

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
    }

    @Test
    @DisplayName("a preflight to a PROTECTED route is answered, not refused for having no token")
    void preflightIsNotAuthenticated() {
        client.options().uri("/api/orders")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    }

    @Test
    @DisplayName("Authorization is an allowed request header, or every authenticated call fails")
    void authorizationHeaderIsPermitted() {
        client.options().uri("/api/orders")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value("Access-Control-Allow-Headers",
                        allowed -> org.assertj.core.api.Assertions.assertThat(allowed)
                                .containsIgnoringCase("authorization"));

        // Not CORS-safelisted, so omitting it from allowed-headers makes every authenticated browser
        // request fail the preflight - and the console says "CORS", not "token".
    }

    @Test
    @DisplayName("an origin nobody allowed is refused")
    void unknownOriginsAreRefused() {
        client.options().uri("/api/orders")
                .header("Origin", "https://evil.example.com")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("CORS IS NOT ACCESS CONTROL - a client that sends no Origin is unaffected")
    void aClientWithoutAnOriginHeaderIsNotSubjectToCors() {
        // The misreading worth pinning as an assertion. CORS is a browser mechanism: the check keys
        // on the Origin header, and curl, every server-side client and every script simply omit it.
        // Removing an origin from the allow-list stops a PAGE reading responses in a browser. It
        // stops nothing else. The authorization that matters is the token, below.
        client.get().uri("/api/orders")
                .header("Authorization", "Bearer " + TestTokens.customer())
                .exchange()
                .expectStatus().isOk();

        client.get().uri("/api/orders")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the actual cross-origin request is checked too, not only the preflight")
    void actualRequestsCarryingADisallowedOriginAreRefused() {
        // Worth knowing because it is easy to assume CORS is preflight-only. Spring's filter rejects
        // the real request as well when it carries an Origin that is not allowed - so a browser gets
        // 403 rather than a response it is then told not to read. It changes nothing for a client
        // that sends no Origin at all.
        client.method(HttpMethod.GET).uri("/api/orders")
                .header("Origin", "https://evil.example.com")
                .header("Authorization", "Bearer " + TestTokens.customer())
                .exchange()
                .expectStatus().isForbidden();
    }
}
