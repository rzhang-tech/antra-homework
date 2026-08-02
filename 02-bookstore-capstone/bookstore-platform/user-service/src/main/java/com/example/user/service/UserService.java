package com.example.user.service;

import com.example.user.dto.LoginRequestDto;
import com.example.user.dto.LoginResponseDto;
import com.example.user.dto.RegisterRequestDto;
import com.example.user.dto.UserResponseDto;

public interface UserService {

    /** Registers a new customer. The caller cannot choose a role — the server assigns USER. */
    UserResponseDto register(RegisterRequestDto request);

    /** Verifies the credentials and issues a JWT. */
    LoginResponseDto login(LoginRequestDto request);

    /** The user behind the current token. */
    UserResponseDto findByUsername(String username);
}
