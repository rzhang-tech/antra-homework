package com.example.user.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * user-service's filter chain — the only one on the platform that can authenticate a password.
 *
 * <p>The catalog rules are gone: this service has no idea books exist. Each service now protects the
 * routes it actually serves, which is a real improvement on the monolith's single block listing every
 * endpoint in the system. The cost is that "what is public across the platform?" is no longer
 * answerable from one file — Step 8's gateway restores a single place to ask.
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
                         * Actuator, split by what each endpoint can do (Step 6c).
                         *
                         * /actuator/health stays open because the thing that calls it cannot carry a
                         * token: a Kubernetes liveness probe has no credentials, and a health check
                         * that answers 401 is a pod that never becomes ready.
                         *
                         * Everything else needs ADMIN. /actuator/refresh is a POST that re-reads
                         * configuration and rebinds beans in a running process, and /actuator/env
                         * discloses the whole shape of the configuration. Leaving those on the same
                         * "internal network, so no token" reasoning as /health confuses "safe to read
                         * from a probe" with "safe to let anyone invoke".
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
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Registering and logging in cannot require being logged in.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login")
                            .permitAll()
                        // Listing every user is staff-only; a customer may still read their own profile.
                        .requestMatchers(HttpMethod.GET, "/api/users").hasRole("ADMIN")
                        .anyRequest().authenticated())

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    /**
     * Only this service has one.
     *
     * <p>{@code DaoAuthenticationProvider} pairs {@link CustomUserDetailsService} with the
     * {@link PasswordEncoder}: load the stored hash by username, then verify the submitted password.
     * It also hashes a dummy password when the user does not exist, so login takes comparable time
     * either way and response timing does not reveal which usernames are real.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
