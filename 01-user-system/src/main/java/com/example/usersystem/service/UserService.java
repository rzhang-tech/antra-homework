package com.example.usersystem.service;

import com.example.usersystem.dto.UserRequestDto;
import com.example.usersystem.dto.UserResponseDto;

import java.util.List;

public interface UserService {
    UserResponseDto createUser(UserRequestDto request);        // new user
    UserResponseDto getUserById(Long id);                      // find 1
    List<UserResponseDto> getAllUsers();                       // find all
    List<UserResponseDto> searchByUsername(String keyword);    // search depends keyword
    UserResponseDto updateUser(Long id, UserRequestDto request);// update
    void deleteUser(Long id);                                  // delete
}
