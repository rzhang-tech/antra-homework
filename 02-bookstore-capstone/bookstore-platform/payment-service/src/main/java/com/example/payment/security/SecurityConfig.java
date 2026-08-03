package com.example.payment.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * payment-service's filter chain. Like book-service it verifies tokens and cannot issue them.
 *
 * <p>Nothing here is public. A catalog has anonymous browsers; an order always belongs to somebody.
 *
 * <p>The rules stop at the role level. "Only the owner may read this order" cannot be decided from the
 * URL — it needs the row — so that check lives in {@code OrderServiceImpl}, next to the data it depends
 * on. Route rules for what the URL can answer, service-level checks for what only the data can.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorWriter securityErrorWriter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorWriter)
                        .accessDeniedHandler(securityErrorWriter))

                .authorizeHttpRequests(auth -> auth
                        /*
                         * Operational endpoints, open on this profile.
                         *
                         * The deny-by-default rule caught these first, which was correct and
                         * inconvenient in equal measure: circuit-breaker state has to be readable by
                         * whoever is diagnosing an outage, and at that moment nobody is minting a JWT.
                         *
                         * Left open here because in a real deployment these are not exposed publicly at
                         * all — they are reachable only from inside the cluster, and the gateway (Step
                         * 8) never routes /actuator/** from outside. Relying on network topology rather
                         * than on a token is the normal arrangement for health and metrics, and Step 11
                         * revisits it when the endpoints start carrying more.
                         */
                        // /actuator/prometheus is permitted alongside health, and the reasoning is
                        // the same one Step 10c used for the gateway's management port: the boundary
                        // is the Service definition, not this filter chain. No service's own port is
                        // published outside the cluster, so this endpoint is reachable from other
                        // pods and from nowhere else.
                        //
                        // The alternative was a token in Prometheus's scrape config, which means a
                        // long-lived credential in a ConfigMap - strictly worse than relying on a
                        // network boundary that already exists.
                        //
                        // What it is honest to admit: metrics disclose URI templates, request counts
                        // and error rates, and there is no NetworkPolicy in this namespace, so any
                        // pod can read them. That is the same gap 10c listed under "what got worse",
                        // not a new one, and a NetworkPolicy closes both.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus",
                                "/actuator/circuitbreakers", "/actuator/circuitbreakerevents").permitAll()

                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Listing every order on the platform is staff-only. Declared before the
                        // general /api/orders rules because the first match wins.
                        .requestMatchers("/api/payments/**").hasAnyRole("USER", "ADMIN")
                        .anyRequest().authenticated())

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }
}
