package com.example.user.service;

import com.example.user.config.JwtProperties;
import com.example.user.dto.LoginRequestDto;
import com.example.user.dto.LoginResponseDto;
import com.example.user.dto.RegisterRequestDto;
import com.example.user.dto.UserResponseDto;
import com.example.user.entity.Role;
import com.example.user.entity.User;
import com.example.user.exception.DuplicateResourceException;
import com.example.user.repository.UserRepository;
import com.example.user.security.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl")
class UserServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtUtil jwtUtil;

    // A record, so it is constructed rather than mocked — there is no behaviour to stub.
    private final JwtProperties jwtProperties = new JwtProperties("x".repeat(64), 60, "bookstore");

    private UserServiceImpl userService;

    private UserServiceImpl service() {
        if (userService == null) {
            userService = new UserServiceImpl(
                    userRepository, passwordEncoder, authenticationManager, jwtUtil, jwtProperties);
        }
        return userService;
    }

    private static RegisterRequestDto registration() {
        return new RegisterRequestDto("ruoyu", "ruoyu@example.com", "correct-horse-battery");
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("stores the hash, never the password")
        void hashesPassword() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode("correct-horse-battery")).thenReturn("$2a$10$fake-hash");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service().register(registration());

            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(saved.capture());
            assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$fake-hash");
            assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("correct-horse-battery");
        }

        @Test
        @DisplayName("always assigns USER — a client cannot register as ADMIN")
        void alwaysAssignsUserRole() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$fake-hash");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UserResponseDto result = service().register(registration());

            assertThat(result.role()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("never puts the hash in the response")
        void responseHasNoHash() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$fake-hash");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            UserResponseDto result = service().register(registration());

            // The DTO has no such component; asserting on its rendering is the closest a unit test
            // gets to "the hash cannot leak through this endpoint".
            assertThat(result.toString()).doesNotContain("$2a$10$fake-hash");
        }

        @Test
        @DisplayName("rejects a taken username before hashing anything")
        void rejectsDuplicateUsername() {
            when(userRepository.existsByUsername("ruoyu")).thenReturn(true);

            assertThatThrownBy(() -> service().register(registration()))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("ruoyu");

            verify(passwordEncoder, never()).encode(anyString());
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects an email already registered")
        void rejectsDuplicateEmail() {
            when(userRepository.existsByUsername(anyString())).thenReturn(false);
            when(userRepository.existsByEmail("ruoyu@example.com")).thenReturn(true);

            assertThatThrownBy(() -> service().register(registration()))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("ruoyu@example.com");

            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("issues a token carrying the user's role and expiry")
        void issuesToken() {
            User user = User.builder()
                    .id(1L).username("ruoyu").email("ruoyu@example.com")
                    .passwordHash("$2a$10$fake-hash").role(Role.USER).build();
            when(userRepository.findByUsername("ruoyu")).thenReturn(Optional.of(user));
            when(jwtUtil.generate(user)).thenReturn("a.signed.token");

            LoginResponseDto result = service().login(
                    new LoginRequestDto("ruoyu", "correct-horse-battery"));

            assertThat(result.token()).isEqualTo("a.signed.token");
            assertThat(result.tokenType()).isEqualTo("Bearer");
            assertThat(result.expiresInSeconds()).isEqualTo(3600);
            assertThat(result.role()).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("does not issue a token when authentication fails")
        void noTokenOnBadCredentials() {
            when(authenticationManager.authenticate(any()))
                    .thenThrow(new BadCredentialsException("Bad credentials"));

            assertThatThrownBy(() -> service().login(
                    new LoginRequestDto("ruoyu", "wrong-password")))
                    .isInstanceOf(BadCredentialsException.class);

            verify(jwtUtil, never()).generate(any());
        }
    }
}
