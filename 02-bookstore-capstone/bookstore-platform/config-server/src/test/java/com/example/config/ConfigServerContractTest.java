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
        assertThat(merged.path("app.jwt.secret").asText()).isNotBlank();
        assertThat(merged.path("app.jwt.issuer").asText()).isEqualTo("bookstore");
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
