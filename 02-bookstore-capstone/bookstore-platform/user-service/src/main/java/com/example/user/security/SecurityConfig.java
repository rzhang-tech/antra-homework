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
