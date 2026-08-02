package com.example.bookstore.service;

import com.example.bookstore.dto.RegisterRequestDto;
import com.example.bookstore.dto.UserResponseDto;
import com.example.bookstore.entity.Role;
import com.example.bookstore.entity.User;
import com.example.bookstore.exception.DuplicateResourceException;
import com.example.bookstore.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

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
}
