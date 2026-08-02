package com.example.bookstore.service;

import com.example.bookstore.dto.RegisterRequestDto;
import com.example.bookstore.dto.UserResponseDto;

public interface UserService {

    /** Registers a new customer. The caller cannot choose a role — the server assigns USER. */
    UserResponseDto register(RegisterRequestDto request);
}
