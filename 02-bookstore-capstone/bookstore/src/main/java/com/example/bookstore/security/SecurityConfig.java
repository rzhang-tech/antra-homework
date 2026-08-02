package com.example.bookstore.security;

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

                /*
                 * Authorization rules, evaluated top to bottom — the FIRST match wins, so the specific
                 * patterns must precede the general ones. Putting `anyRequest()` anywhere but last is a
                 * compile-time error, but ordering mistakes among the rest are silent: a broad rule
                 * placed early quietly swallows the narrow ones below it.
                 *
                 * Keeping them in one block is deliberate. Authorization spread across dozens of
                 * @PreAuthorize annotations cannot be reviewed as a whole, and "which endpoints are
                 * public?" stops being a question anyone can answer by reading. Method security is the
                 * right tool for rules that depend on the data — "only the owner of this order" —
                 * which is a Step 5 problem.
                 */
                .authorizeHttpRequests(auth -> auth
                        // Registering and logging in cannot require being logged in.
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login")
                            .permitAll()

                        // Browsing the catalog is public — an anonymous visitor must be able to shop
                        // before deciding to register.
                        .requestMatchers(HttpMethod.GET, "/api/books", "/api/books/*", "/api/authors")
                            .permitAll()

                        // Buying requires an account. Both roles are named explicitly because Spring
                        // Security roles are NOT hierarchical: hasRole("USER") alone would give an
                        // ADMIN a 403 here, since ADMIN does not "include" USER unless a RoleHierarchy
                        // says so.
                        .requestMatchers(HttpMethod.POST, "/api/books/*/purchase")
                            .hasAnyRole("USER", "ADMIN")

                        // Managing the catalog is staff-only.
                        .requestMatchers(HttpMethod.POST, "/api/books").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/books/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/books/*").hasRole("ADMIN")

                        // Deny by default. Anything not listed above needs a token — so a route added
                        // later is closed until someone deliberately opens it, rather than public until
                        // someone notices.
                        .anyRequest().authenticated())

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
