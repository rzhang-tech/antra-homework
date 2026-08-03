package com.example.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What this server promises the other four services.
 *
 * <p>There is no business logic here to unit-test - the module is one annotation. What is worth testing
 * is the <em>contract</em>: which files answer a request, and in what order they are allowed to override
 * each other. Both are decisions encoded in file names rather than in code, which means the compiler
 * checks neither and a rename breaks a service at startup with no warning at build time.
 *
 * <p>These run without Docker and without any other service: the native backend reads
 * {@code ../config-repo} straight off disk.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerContractTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("profile beats specificity - the ordering everybody guesses wrong")
    void profileSpecificSharedFileOutranksTheServiceOwnFile() {
        assertThat(propertySourceNamesFor("/user-service/dev")).containsExactly(
                "user-service-dev.yml",   // highest priority
                "application-dev.yml",    // and THIS is the surprise: it outranks the next line
                "user-service.yml",
                "application.yml"         // platform-wide default, lowest priority
        );

        // The consequence, stated as an assertion so it cannot quietly stop being true: putting a key
        // in the shared application-dev.yml overrides the same key in user-service.yml. A service
        // trying to override a dev-profile default from its own non-profile file will lose, silently.
    }

    @Test
    @DisplayName("a service receives its own database credentials and the shared signing key")
    void servesEverythingUserServiceNoLongerCarriesInItsJar() {
        JsonNode merged = mergedSourceFor("/user-service/dev");

        assertThat(merged.path("spring.datasource.url").asText()).contains("5433/userdb");
        assertThat(merged.path("server.port").asInt()).isEqualTo(8081);
        assertThat(merged.path("app.jwt.issuer").asText()).isEqualTo("bookstore");
    }

    @Test
    @DisplayName("centralising configuration does not soften Database-per-Service")
    void noServiceIsEverSentAnotherServicesCredentials() {
        // The obvious worry about a config server is that it becomes a place where every service's
        // secrets sit together, one lookup away from each other. It does hold all four datasource
        // blocks - but it answers only for the application name that asked, so the isolation Step 5a
        // built out of separate ports and separate passwords survives intact.
        record Service(String name, String database, int port) {}

        List<Service> services = List.of(
                new Service("user-service", "userdb", 5433),
                new Service("book-service", "bookdb", 5434),
                new Service("order-service", "orderdb", 5435),
                new Service("payment-service", "paymentdb", 5436));

        for (Service service : services) {
            ObjectNode config = mergedSourceFor("/" + service.name() + "/dev");
            String url = config.path("spring.datasource.url").asText();

            assertThat(url).contains(service.port() + "/" + service.database());
            assertThat(config.path("spring.datasource.username").asText()).isEqualTo(service.database());

            for (Service other : services) {
                if (!other.equals(service)) {
                    assertThat(url).doesNotContain(other.database());
                }
            }
        }
    }

    @Test
    @DisplayName("all four services are handed the same signing key, and only with the key to decrypt it")
    void theKeyEveryServiceMustAgreeOnIsOneValue() {
        // Two halves of one contract, and which half applies depends on how this JVM was started.
        //
        // The duplication Step 6 exists to remove is four byte-identical copies of one literal that
        // nothing enforced: if they drifted, user-service would mint tokens the other three rejected,
        // and the symptom would be 401s rather than a configuration error.
        //
        // Since 6d that literal is a `{cipher}` value, so the server can only produce it when it holds
        // ENCRYPT_KEY. Both outcomes are worth pinning - the second one is the promise that a config
        // server without its key discloses nothing rather than serving something half-usable.
        List<String> serviceNames =
                List.of("user-service", "book-service", "order-service", "payment-service");

        if (System.getenv("ENCRYPT_KEY") == null) {
            for (String name : serviceNames) {
                ObjectNode config = mergedSourceFor("/" + name + "/dev");
                assertThat(config.has("app.jwt.secret"))
                        .as("no plaintext key without ENCRYPT_KEY")
                        .isFalse();
                assertThat(config.path("invalid.app.jwt.secret").asText()).isEqualTo("<n/a>");
            }
            return;
        }

        List<String> keys = serviceNames.stream()
                .map(name -> mergedSourceFor("/" + name + "/dev").path("app.jwt.secret").asText())
                .toList();

        assertThat(keys).doesNotContain("").hasSize(4);
        assertThat(keys).containsOnly(keys.getFirst());
    }


    @Test
    @DisplayName("the gateway's real route table sends each path to the service that owns it")
    void everyApiPathIsRoutedToItsOwner() {
        // The half RoutingTest cannot cover. api-gateway's tests run against a stand-in route table,
        // because the real one lives here and tests do not read the config server - so without this,
        // renaming a path in the config repo would break every client and pass every test.
        ObjectNode routes = mergedSourceFor("/api-gateway/dev");

        record Route(String service, String paths, String address) {}

        List<Route> expected = List.of(
                new Route("user-service", "/api/auth/**,/api/users/**", "8081"),
                new Route("book-service", "/api/books/**,/api/authors/**", "8082"),
                new Route("order-service", "/api/orders/**", "8083"),
                new Route("payment-service", "/api/payments/**", "8084"));

        for (int i = 0; i < expected.size(); i++) {
            Route route = expected.get(i);
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + i + "]";

            assertThat(routes.path(prefix + ".id").asText()).isEqualTo(route.service());
            assertThat(routes.path(prefix + ".predicates[0]").asText())
                    .isEqualTo("Path=" + route.paths());

            // The URI is a placeholder the gateway resolves against its own environment, which is what
            // lets prod swap addresses for Kubernetes service names without touching the route table.
            String placeholder = routes.path(prefix + ".uri").asText();
            assertThat(placeholder).startsWith("${app.services.").endsWith(".url}");

            String key = placeholder.substring(2, placeholder.length() - 1);

            // `contains`, not `endsWith`. Since 10b the value is itself a placeholder with a default -
            // `${USER_SERVICE_URL:http://localhost:8081}` - because the dev profile now also runs as a
            // set of containers, where every laptop address is wrong. What is still worth pinning is
            // that the default sends this route to the port that service actually listens on: a route
            // table that compiles and points at the wrong service is the failure this test exists for.
            assertThat(routes.path(key).asText()).contains(":" + route.address());
        }

        // Index order is asserted along with the contents, because Gateway takes the first predicate
        // that matches. A broad path moved above a narrow one silently swallows it, and nothing warns.
        assertThat(routes.has("spring.cloud.gateway.server.webflux.routes[4].id")).isFalse();
    }

    @Test
    @DisplayName("an unknown application gets the shared defaults, not an error")
    void unknownApplicationFallsBackToSharedFilesOnly() {
        // Config Server answers 200 with whatever matches, so a typo in spring.application.name does
        // not fail loudly - it produces a service with no datasource that dies later, somewhere else.
        // Pinned here so the behaviour is a known hazard rather than a discovery during an incident.
        assertThat(propertySourceNamesFor("/typo-in-the-name/dev"))
                .containsExactly("application-dev.yml", "application.yml");
    }

    private JsonNode environmentFor(String path) {
        JsonNode body = rest.getForObject(path, JsonNode.class);
        assertThat(body).isNotNull();
        return body;
    }

    private List<String> propertySourceNamesFor(String path) {
        List<String> names = new ArrayList<>();
        // Assert on the file name only: the full value is a file: URL containing the working directory,
        // which differs between a Maven run and an IDE run and is not part of the contract.
        for (JsonNode source : environmentFor(path).path("propertySources")) {
            String name = source.path("name").asText();
            names.add(name.substring(name.lastIndexOf('/') + 1));
        }
        return names;
    }

    /** Flattens the ordered property sources the way a client does: highest priority wins. */
    private ObjectNode mergedSourceFor(String path) {
        List<JsonNode> sources = new ArrayList<>();
        environmentFor(path).path("propertySources").forEach(sources::add);

        // Walk from the bottom up, so higher-priority sources overwrite what lower ones set.
        ObjectNode merged = JsonNodeFactory.instance.objectNode();
        for (int i = sources.size() - 1; i >= 0; i--) {
            sources.get(i).path("source").properties()
                    .forEach(entry -> merged.set(entry.getKey(), entry.getValue()));
        }
        return merged;
    }
}
