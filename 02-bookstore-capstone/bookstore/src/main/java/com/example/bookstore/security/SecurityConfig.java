package com.example.bookstore.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorWriter securityErrorWriter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                /*
                 * CSRF protection defends against a browser being tricked into submitting a request
                 * using cookies it already holds. It is disabled because this API is stateless and
                 * authenticates with a Bearer token — which the browser does not attach automatically,
                 * so the attack has nothing to ride on. Disabling it on a cookie-authenticated app
                 * would be a serious mistake.
                 */
                .csrf(csrf -> csrf.disable())

                // No HTTP session: nothing about a request is remembered between requests, and the
                // token carries the identity. This is what lets any instance serve any request — no
                // sticky sessions, no shared session store, and the precondition for Steps 8 and 10.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // A REST client wants a status code, not a redirect to a login page.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorWriter)   // 401
                        .accessDeniedHandler(securityErrorWriter))       // 403

                .authorizeHttpRequests(auth -> auth
                        // Registering and logging in cannot require being logged in.
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        // The first genuinely protected route.
                        .requestMatchers("/api/auth/me").authenticated()
                        // Everything else stays open until 3c applies the real role rules.
                        .anyRequest().permitAll())

                /*
                 * Our filter runs before UsernamePasswordAuthenticationFilter — the position, not that
                 * specific filter, is what matters. It has to execute before the authorization check so
                 * that the SecurityContext is already populated when the rules above are evaluated.
                 */
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                .build();
    }

    /**
     * The manager the login endpoint calls.
     *
     * <p>{@code DaoAuthenticationProvider} pairs the {@link CustomUserDetailsService} with the
     * {@link PasswordEncoder}: load the stored hash by username, then verify the submitted password
     * against it. It also hashes a dummy password when the user does not exist, so login takes
     * comparable time either way and response timing does not reveal which usernames are real.
     */
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
