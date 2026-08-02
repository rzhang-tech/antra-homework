package com.example.user.security;

import com.example.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Bridges our {@code User} entity to the {@code UserDetails} contract Spring Security understands.
 *
 * <p>Used at <strong>login only</strong>. {@code DaoAuthenticationProvider} calls this to fetch the
 * stored hash, then compares it to the submitted password using the {@code PasswordEncoder}. Later
 * requests authenticate from the token and never reach this class — see {@link JwtAuthenticationFilter}.
 *
 * <p>The {@code ROLE_} prefix is Spring Security's convention, not ours. {@code hasRole("ADMIN")} looks
 * for an authority literally named {@code ROLE_ADMIN}; omitting the prefix here makes every role check
 * fail silently — no error, just a 403 that appears to make no sense.
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.getUsername())
                        .password(user.getPasswordHash())
                        .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())))
                        .build())
                // The message names the username, but the login endpoint must never pass it through to
                // the client: "no such user" versus "wrong password" tells an attacker which usernames
                // are real. AuthController returns one generic message for both.
                .orElseThrow(() -> new UsernameNotFoundException("No user named " + username));
    }
}
