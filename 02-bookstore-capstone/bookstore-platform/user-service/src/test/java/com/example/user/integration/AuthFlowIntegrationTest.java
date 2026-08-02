package com.example.user.integration;

import com.example.user.TestcontainersConfig;
import com.example.user.config.JwtProperties;
import com.example.user.dto.LoginResponseDto;
import com.example.user.dto.UserResponseDto;
import com.example.user.entity.Role;
import com.example.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-service end to end: register, log in, and use the resulting token.
 *
 * <p>The other half of what the monolith's single integration test used to cover. That test walked from
 * registration all the way to a purchase; the two halves now live in the services that own them, and
 * neither can fail because of the other being unavailable.
 *
 * <p>The last test verifies the property everything else on the platform depends on: the token this
 * service issues really is signed with the shared key, so any service holding that key can verify it
 * without ever calling here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@DisplayName("Auth flow (user-service, end to end)")
class AuthFlowIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProperties jwtProperties;

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

    private String uniqueName(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private ResponseEntity<UserResponseDto> register(String username, String password) {
        return rest.postForEntity("/api/auth/register", new HttpEntity<>(Map.of(
                "username", username,
                "email", username + "@example.com",
                "password", password), json()), UserResponseDto.class);
    }

    @Test
    @DisplayName("register -> login -> /me, with the hash never leaving the server")
    void registerLoginAndReadProfile() {
        String username = uniqueName("customer");

        ResponseEntity<UserResponseDto> registered = register(username, "correct-horse-battery");
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody().role()).isEqualTo(Role.USER);

        // The stored value is a hash, and it is not what was sent.
        String stored = userRepository.findByUsername(username).orElseThrow().getPasswordHash();
        assertThat(stored).startsWith("$2a$").isNotEqualTo("correct-horse-battery");

        ResponseEntity<LoginResponseDto> login = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", "correct-horse-battery"),
                        json()), LoginResponseDto.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<UserResponseDto> me = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(bearer(login.getBody().token())), UserResponseDto.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(me.getBody().username()).isEqualTo(username);
    }

    @Test
    @DisplayName("wrong password and unknown user return the identical response")
    void failedLoginsAreIndistinguishable() {
        String username = uniqueName("victim");
        register(username, "correct-horse-battery");

        ResponseEntity<String> wrongPassword = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", "nope"), json()),
                String.class);
        ResponseEntity<String> noSuchUser = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", "definitely-not-a-user", "password", "nope"),
                        json()), String.class);

        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noSuchUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Distinguishing them would turn this endpoint into a list of which accounts exist.
        assertThat(wrongPassword.getBody()).contains("Invalid username or password");
        assertThat(noSuchUser.getBody()).contains("Invalid username or password");
    }

    @Test
    @DisplayName("a client cannot register itself as ADMIN by sending a role")
    void roleCannotBeChosenByTheClient() {
        String username = uniqueName("wannabe");

        ResponseEntity<UserResponseDto> response = rest.postForEntity("/api/auth/register",
                new HttpEntity<>(Map.of(
                        "username", username,
                        "email", username + "@example.com",
                        "password", "correct-horse-battery",
                        "role", "ADMIN"), json()), UserResponseDto.class);

        assertThat(response.getBody().role()).isEqualTo(Role.USER);
        assertThat(userRepository.findByUsername(username).orElseThrow().getRole())
                .isEqualTo(Role.USER);
    }

    @Test
    @DisplayName("the issued token verifies against the shared key — how other services trust it")
    void issuedTokenVerifiesWithTheSharedKey() {
        String username = uniqueName("holder");
        register(username, "correct-horse-battery");

        String token = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", "correct-horse-battery"),
                        json()), LoginResponseDto.class).getBody().token();

        // Exactly what book-service does on every request: verify the signature with the shared key and
        // read the claims. No call back to this service is involved, which is the entire point.
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        var claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(jwtProperties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(claims.getSubject()).isEqualTo(username);
        assertThat(claims.get("role", String.class)).isEqualTo("USER");
    }
}
