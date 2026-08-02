package com.example.bookstore.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Step 3a: the security starter is on the classpath, so Spring Security is active — and its defaults
 * would lock every endpoint behind a login form and a password printed to the console. This config
 * replaces those defaults with ones appropriate to a REST API.
 *
 * <p>Authentication and authorization rules arrive in 3b and 3c. Right now every route is open, exactly
 * as it was before the starter was added; the only change is that the framework is wired in and ready.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                /*
                 * CSRF protection defends against a browser being tricked into submitting a form using
                 * cookies it already holds. It is disabled here because this API is stateless and will
                 * authenticate with a Bearer token in Step 3b — a token the browser does not attach
                 * automatically, which is what makes the attack impossible in the first place.
                 * Disabling it on a cookie-authenticated app would be a serious mistake.
                 */
                .csrf(csrf -> csrf.disable())

                // No HTTP session. Nothing about a request is remembered between requests; from 3b the
                // token carries the identity. This is what lets the service scale horizontally later —
                // any instance can serve any request, with no sticky sessions or shared session store.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // A REST client wants a status code, not a redirect to a login page.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                // 3c replaces this with the real rules.
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())

                .build();
    }
}
