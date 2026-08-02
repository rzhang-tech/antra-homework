package com.example.bookstore.service;

import com.example.bookstore.config.JwtProperties;
import com.example.bookstore.dto.LoginRequestDto;
import com.example.bookstore.dto.LoginResponseDto;
import com.example.bookstore.dto.RegisterRequestDto;
import com.example.bookstore.dto.UserResponseDto;
import com.example.bookstore.entity.Role;
import com.example.bookstore.entity.User;
import com.example.bookstore.exception.DuplicateResourceException;
import com.example.bookstore.exception.ResourceNotFoundException;
import com.example.bookstore.repository.UserRepository;
import com.example.bookstore.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProperties;

    @Override
    @Transactional
    public UserResponseDto register(RegisterRequestDto request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username '" + request.username() + "' is taken");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email '" + request.email() + "' is already registered");
        }

        User user = User.builder()
                .username(request.username())
                .email(request.email())
                // The one and only place the plaintext password is touched. It is hashed here and the
                // original is never assigned to a field, logged, or returned.
                .passwordHash(passwordEncoder.encode(request.password()))
                // Assigned by the server, never taken from the request. See RegisterRequestDto.
                .role(Role.USER)
                .build();

        return UserResponseDto.from(userRepository.save(user));
    }

    /**
     * Authenticates and mints a token.
     *
     * <p>The password comparison is delegated to the {@code AuthenticationManager} rather than done by
     * hand. It runs {@code DaoAuthenticationProvider}, which loads the user through
     * {@code CustomUserDetailsService} and calls {@code PasswordEncoder.matches} — and, importantly,
     * performs a dummy hash when the user does not exist, so a login attempt takes the same time
     * whether or not the username is real. Hand-rolled comparison usually leaks that difference and
     * turns response time into a username oracle.
     */
    @Override
    @Transactional(readOnly = true)
    public LoginResponseDto login(LoginRequestDto request) {
        // Throws BadCredentialsException for both a wrong password and an unknown user.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        User user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        return LoginResponseDto.of(
                jwtUtil.generate(user),
                jwtProperties.expirationMinutes() * 60,
                user.getUsername(),
                user.getRole());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponseDto findByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(UserResponseDto::from)
                // Reachable only with a validly-signed token for a user who has since been deleted —
                // the staleness window the stateless design accepts. See JwtAuthenticationFilter.
                .orElseThrow(() -> new ResourceNotFoundException("No user named " + username));
    }
}
