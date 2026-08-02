package com.example.book.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * book-service's filter chain.
 *
 * <p>Compared with the monolith's, one thing is conspicuously missing: the {@code AuthenticationManager}
 * bean, and with it {@code CustomUserDetailsService} and {@code PasswordEncoder}. This service has no
 * login endpoint, no access to the users table, and no way to verify a password. It can only decide
 * whether a token someone else issued is genuine.
 *
 * <p>That is what statelessness buys. Authorization needs the signature and the claims, not a user
 * lookup — so the catalog can enforce "ADMIN only" without any ability to read, let alone create, a
 * user. In Step 8 the gateway performs the same check one hop earlier, and this chain remains as the
 * defence in depth behind it.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorWriter securityErrorWriter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless, Bearer-token API: the browser attaches nothing automatically, so CSRF has
                // nothing to ride on. On a cookie-authenticated app this would be a serious mistake.
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorWriter)   // 401
                        .accessDeniedHandler(securityErrorWriter))       // 403

                .authorizeHttpRequests(auth -> auth
                        // Browsing the catalog is public — an anonymous visitor must be able to shop
                        // before deciding to register.
                        .requestMatchers(HttpMethod.GET, "/api/books", "/api/books/*", "/api/authors")
                            .permitAll()

                        // Both roles named explicitly: Spring Security roles are not hierarchical, so
                        // hasRole("USER") alone would give an ADMIN a 403 here.
                        .requestMatchers(HttpMethod.POST, "/api/books/*/purchase")
                            .hasAnyRole("USER", "ADMIN")

                        // Compensation is reachable by whoever could reserve. A failed order that only
                        // a human could unwind is not a compensating action, it is a support ticket.
                        .requestMatchers(HttpMethod.POST, "/api/books/reservations/*/release")
                            .hasAnyRole("USER", "ADMIN")

                        .requestMatchers(HttpMethod.POST, "/api/books").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/books/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/books/*").hasRole("ADMIN")

                        // Deny by default: a route added later is closed until deliberately opened.
                        .anyRequest().authenticated())

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }
}
