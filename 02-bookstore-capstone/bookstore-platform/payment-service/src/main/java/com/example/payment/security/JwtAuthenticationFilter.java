package com.example.payment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Turns a valid {@code Authorization: Bearer <token>} header into an authenticated SecurityContext,
 * with an {@link AuthenticatedUser} as the principal.
 *
 * <p>As in the other services it never rejects anything: an invalid token leaves the context anonymous
 * and the authorization rules decide the status code.
 *
 * <p>It additionally parks the raw token on the request. order-service is the first service that has to
 * call another service <em>on the user's behalf</em>, and the outgoing call needs the same credential —
 * see {@code FeignAuthPropagation}.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** Where the raw token is parked for the Feign interceptor to pick up. */
    public static final String TOKEN_ATTRIBUTE = "com.example.payment.rawToken";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        bearerToken(request).ifPresent(token ->
                jwtUtil.parse(token).ifPresent(claims -> {
                    var principal = new AuthenticatedUser(
                            jwtUtil.userIdOf(claims),
                            jwtUtil.usernameOf(claims),
                            jwtUtil.roleOf(claims));

                    var authentication = new UsernamePasswordAuthenticationToken(
                            principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    request.setAttribute(TOKEN_ATTRIBUTE, token);
                }));

        filterChain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
