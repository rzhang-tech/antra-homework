package com.example.order.client;

import com.example.order.security.JwtAuthenticationFilter;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Forwards the caller's token on every outgoing Feign call.
 *
 * <p>Without this, order-service authenticates the customer perfectly and then calls book-service
 * anonymously — so {@code POST /api/books/{id}/purchase} comes back 401 and placing an order fails for
 * a reason that has nothing to do with orders. Identity does not propagate by itself across a network
 * hop; the monolith never had to think about it because there was no hop.
 *
 * <p><strong>Why forward the user's token rather than use a service account.</strong> book-service's
 * rules are written about <em>people</em>: a customer may purchase, only an admin may edit the catalog.
 * Calling with a service identity would mean either giving order-service permissions broader than any
 * of its callers — a confused-deputy waiting to happen — or duplicating user-facing rules into a
 * service-to-service policy. Forwarding keeps one set of rules, evaluated against the real actor.
 *
 * <p><strong>What it costs.</strong> The downstream call inherits the token's lifetime, so a long
 * operation can fail midway when the token expires. And forwarding is only safe because every service
 * here is inside one trust boundary — a token must never be forwarded to a third party, which would
 * hand them a credential usable against everything else on the platform.
 *
 * <p>The token comes from a request attribute rather than by re-reading the header, so there is exactly
 * one place that decides what the caller's credential is.
 */
@Configuration
public class FeignAuthPropagation {

    @Bean
    public RequestInterceptor authForwardingInterceptor() {
        return (RequestTemplate template) -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (attributes instanceof ServletRequestAttributes servletAttributes) {
                Object token = servletAttributes.getRequest()
                        .getAttribute(JwtAuthenticationFilter.TOKEN_ATTRIBUTE);
                if (token instanceof String raw && !raw.isBlank()) {
                    template.header(HttpHeaders.AUTHORIZATION, "Bearer " + raw);
                }
            }
            // No token, no header. The downstream service then answers as it would for any anonymous
            // caller — 200 for a public read, 401 for anything else — which is the correct outcome
            // rather than something order-service should paper over.
        };
    }
}
