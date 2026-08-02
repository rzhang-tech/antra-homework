package com.example.bookstore.service;

import com.example.bookstore.dto.LoginRequestDto;
import com.example.bookstore.dto.LoginResponseDto;
import com.example.bookstore.dto.RegisterRequestDto;
import com.example.bookstore.dto.UserResponseDto;

public interface UserService {

    /** Registers a new customer. The caller cannot choose a role — the server assigns USER. */
    UserResponseDto register(RegisterRequestDto request);

    /** Verifies the credentials and issues a JWT. */
    LoginResponseDto login(LoginRequestDto request);

    /** The user behind the current token. */
    UserResponseDto findByUsername(String username);
}
