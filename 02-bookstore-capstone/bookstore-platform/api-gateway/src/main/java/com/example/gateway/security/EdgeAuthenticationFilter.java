package com.example.gateway.security;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Answers "who are you?" before a request leaves the building. Never answers "may you?".
 *
 * <h2>The division of labour, which is the whole design</h2>
 *
 * <p><strong>The gateway authenticates; the services authorize.</strong> This filter checks that a
 * token exists, is genuine, and has not expired — and then gets out of the way. It does not know that
 * only an ADMIN may delete a book, or that a customer may read only their own orders, and it must not
 * learn: a rule stated in two places drifts, and the copy on the edge is the one nobody remembers to
 * update when the service changes.
 *
 * <p>What the edge buys by doing only the coarse half: a request with no token, or a forged one, is
 * refused in microseconds without touching a service, a database connection or a thread pool. Under a
 * credential-stuffing attempt that is the difference between an inconvenience and an outage.
 *
 * <h2>Why the services still verify every token</h2>
 *
 * <p>The tempting next step is to strip the token here, forward {@code X-Auth-User-Id}, and let the
 * services trust it. Do not. Anything that can reach a service directly — another pod, a
 * port-forward, a misconfigured NetworkPolicy, a compromised sidecar — could then claim to be
 * anybody, and the platform's entire authorization model would rest on network topology that nothing
 * enforces. **A network boundary is not a security boundary until something makes it one** (mTLS, a
 * service mesh, an authenticated internal identity), and this platform has none of those yet.
 *
 * <p>So the token is forwarded untouched and every service verifies it exactly as it did before Step
 * 8. The headers below are for logging and tracing, never for authorization, and the services ignore
 * them entirely. This is demonstrable rather than theoretical: curl a service directly on 8082 with a
 * hand-written {@code X-Auth-Role: ADMIN} and it still answers 401, because the signature is what it
 * checks.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EdgeAuthenticationFilter implements GlobalFilter, Ordered {

    /** Set by this filter, and by nothing else. See {@link #stripClientSuppliedIdentity}. */
    public static final String USER_HEADER = "X-Auth-User";
    public static final String USER_ID_HEADER = "X-Auth-User-Id";
    public static final String ROLE_HEADER = "X-Auth-Role";

    private static final List<String> IDENTITY_HEADERS =
            List.of(USER_HEADER, USER_ID_HEADER, ROLE_HEADER);

    private static final String BEARER = "Bearer ";

    private final GatewayJwtVerifier verifier;
    private final AntPathMatcher paths = new AntPathMatcher();

    /**
     * What a visitor may reach without introducing themselves.
     *
     * <p>Kept deliberately short, and matched on method as well as path. It mirrors the {@code
     * permitAll} rules the services already have — registering and logging in cannot require being
     * logged in, and browsing the catalogue is public because an anonymous visitor has to be able to
     * shop before deciding to register.
     *
     * <p><strong>This list is the one duplication Step 8 accepts</strong>, and it is worth naming the
     * risk: if book-service later closes {@code GET /api/books} and nobody edits this list, the edge
     * lets the request through and the service refuses it. That failure is safe — the service is the
     * authority and it says no — which is precisely why the duplication is tolerable in this
     * direction and would not be in the other. An edge that *granted* access a service denied would be
     * a vulnerability; an edge that grants passage to a service that then denies it is only a wasted
     * hop.
     */
    private record PublicRoute(HttpMethod method, String pattern) {}

    private static final List<PublicRoute> PUBLIC = List.of(
            new PublicRoute(HttpMethod.POST, "/api/auth/register"),
            new PublicRoute(HttpMethod.POST, "/api/auth/login"),
            new PublicRoute(HttpMethod.GET, "/api/books"),
            new PublicRoute(HttpMethod.GET, "/api/books/*"),
            // Added in Step 9b, one step after the risk above was written down - and in the direction
            // that comment did NOT cover. It predicted a service CLOSING a route while the edge stayed
            // open, and called that safe because the service is the authority. What actually happened
            // was a service OPENING one: book-service made cover retrieval public, this list did not
            // know, and the front door answered 401 to an endpoint the service was happy to serve.
            //
            // Not a security failure - a functionality failure, which is the other half of the same
            // duplication. Worth stating that the safe direction is only safe for security: an edge
            // that is stricter than the services it fronts is an edge that makes them unreachable.
            new PublicRoute(HttpMethod.GET, "/api/books/*/cover"),
            new PublicRoute(HttpMethod.GET, "/api/authors"));

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // FIRST, unconditionally, and before any decision: a client does not get to say who it is.
        ServerHttpRequest sanitised = stripClientSuppliedIdentity(request);

        if (isPublic(sanitised)) {
            // Still sanitised. A public route reaching a service with a client-supplied X-Auth-Role
            // would be the same hole, arriving through the one door nobody guards.
            return chain.filter(exchange.mutate().request(sanitised).build());
        }

        Optional<String> token = bearerTokenOf(sanitised);
        if (token.isEmpty()) {
            return refuse(exchange, "A bearer token is required for this endpoint");
        }

        Optional<Claims> claims = verifier.verify(token.get());
        if (claims.isEmpty()) {
            return refuse(exchange, "The bearer token is invalid or has expired");
        }

        return chain.filter(exchange.mutate()
                .request(withIdentityHeaders(sanitised, claims.get()))
                .build());
    }

    /**
     * Removes any {@code X-Auth-*} the caller sent.
     *
     * <p><strong>The single most important line in this class.</strong> Without it, adding
     * {@code X-Auth-Role: ADMIN} to a curl command would be a complete authentication bypass the day
     * any downstream code starts reading these headers — and downstream code reading a header that
     * "the gateway always sets" is exactly how that happens. Headers a proxy sets must be headers a
     * proxy also clears; a trusted header is only trustworthy if the client cannot supply it.
     *
     * <p>Unconditional, including on public routes and on requests that are about to be refused,
     * because the value of this guarantee comes entirely from having no exceptions to reason about.
     */
    private ServerHttpRequest stripClientSuppliedIdentity(ServerHttpRequest request) {
        boolean forged = IDENTITY_HEADERS.stream().anyMatch(h -> request.getHeaders().containsKey(h));
        if (forged) {
            log.warn("Client supplied identity headers on {} {} - stripped. Source: {}",
                    request.getMethod(), request.getPath(),
                    request.getRemoteAddress() == null ? "unknown" : request.getRemoteAddress());
        }
        return request.mutate()
                .headers(headers -> IDENTITY_HEADERS.forEach(headers::remove))
                .build();
    }

    /**
     * Adds the verified identity, and keeps the token.
     *
     * <p>Both, not either. The headers make a downstream log line say <em>who</em> without every
     * service parsing a JWT to write one, and they cost nothing. The token stays because it is what the
     * services actually trust, and because order-service forwards it onward to book-service (5b) —
     * strip it here and a cross-service call inside the platform becomes anonymous.
     */
    private ServerHttpRequest withIdentityHeaders(ServerHttpRequest request, Claims claims) {
        return request.mutate()
                .headers(headers -> {
                    headers.add(USER_HEADER, claims.getSubject());
                    headers.add(ROLE_HEADER, String.valueOf(claims.get("role", String.class)));
                    Object id = claims.get("uid");
                    if (id != null) {
                        headers.add(USER_ID_HEADER, String.valueOf(id));
                    }
                })
                .build();
    }

    private boolean isPublic(ServerHttpRequest request) {
        String path = request.getPath().value();
        return PUBLIC.stream().anyMatch(route ->
                route.method().equals(request.getMethod()) && paths.match(route.pattern(), path));
    }

    private Optional<String> bearerTokenOf(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    /**
     * The same error shape every service produces, written by hand.
     *
     * <p>A client should not be able to tell from the response body whether a 401 came from the edge
     * or from a service; a different shape at the gateway would be an information leak about the
     * platform's internals and a second format for every client to parse. Hand-written because
     * importing the services' {@code ErrorResponse} would mean a shared jar (D12).
     *
     * <p>The message never distinguishes "no token" from "bad token" beyond what is written below, and
     * never says <em>why</em> a token failed — expired, wrong issuer, bad signature. That detail is in
     * this service's debug log and nowhere a caller can read it.
     */
    private Mono<Void> refuse(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"timestamp":"%s","status":401,"error":"Unauthorized","message":"%s","path":"%s"}"""
                .formatted(Instant.now(), message, exchange.getRequest().getPath().value());

        return response.writeWith(Mono.just(
                response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * Before the routing filter, and before anything that could act on the request.
     *
     * <p>{@code HIGHEST_PRECEDENCE + 100} rather than {@code HIGHEST_PRECEDENCE}: CORS preflight
     * handling has to run first, because a browser sends {@code OPTIONS} with no {@code Authorization}
     * header at all and a 401 to a preflight makes the real request never happen. That is Step 8c's
     * problem, and leaving room for it here is cheaper than discovering the ordering later.
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
