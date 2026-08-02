package com.example.user.controller;

import com.example.user.dto.LoginRequestDto;
import com.example.user.dto.LoginResponseDto;
import com.example.user.dto.RegisterRequestDto;
import com.example.user.dto.UserResponseDto;
import com.example.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /** Register a new customer. Public — this is how someone becomes a user in the first place. */
    @PostMapping("/register")
    public ResponseEntity<UserResponseDto> register(@Valid @RequestBody RegisterRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.register(request));
    }

    /** Exchange credentials for a JWT. Public, by necessity. */
    @PostMapping("/login")
    public LoginResponseDto login(@Valid @RequestBody LoginRequestDto request) {
        return userService.login(request);
    }

    /**
     * The current user, identified by the token.
     *
     * <p>{@code Authentication} is injected by Spring Security from the SecurityContext that
     * {@link com.example.user.security.JwtAuthenticationFilter} populated. The username comes from
     * the signed token, never from a request parameter — otherwise anyone could ask for anyone's
     * profile by changing a query string.
     */
    @GetMapping("/me")
    public UserResponseDto me(Authentication authentication) {
        return userService.findByUsername(authentication.getName());
    }
}
