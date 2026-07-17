package com.example.usersystem.service;

import com.example.usersystem.dto.UserRequestDto;
import com.example.usersystem.dto.UserResponseDto;
import com.example.usersystem.entity.User;
import com.example.usersystem.exception.DuplicateResourceException;
import com.example.usersystem.exception.ResourceNotFoundException;
import com.example.usersystem.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service                          //Service bean
@RequiredArgsConstructor          // Lombok build final Constructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public UserResponseDto createUser(UserRequestDto request) {
        //check same
        userRepository.findByUsername(request.getUsername()).ifPresent(existing -> {
            throw new DuplicateResourceException("Username already exists: " + request.getUsername());
        });

        userRepository.findByEmail(request.getEmail()).ifPresent(existing -> {
            throw new DuplicateResourceException("Email already exists: " + request.getEmail());
        });


        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(request.getPassword())    // with password now,will remove in toResponseDto()
                .fullName(request.getFullName())
                .age(request.getAge())
                .build();

        // save
        User saved = userRepository.save(user);

        // to ResponseDto
        return toResponseDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponseDto getUserById(Long id) {

        User user = findUserOrThrow(id);
        return toResponseDto(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDto> getAllUsers() {
        return userRepository.findAll().stream().map(this::toResponseDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserResponseDto> searchByUsername(String keyword) {
        return userRepository.findByUsernameContainingIgnoreCase(keyword)  // search by keywords
                .stream()
                .map(this::toResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public UserResponseDto updateUser(Long id, UserRequestDto request) {
        // exist?
        User user = findUserOrThrow(id);

        // update
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());
        user.setFullName(request.getFullName());
        user.setAge(request.getAge());


        User updated = userRepository.save(user);
        return toResponseDto(updated);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User user = findUserOrThrow(id);   // no exist ,404
        userRepository.delete(user);       // exist ,del
    }
    //user->UserResponseDto,a support function
    private UserResponseDto toResponseDto(User user) {
        return UserResponseDto.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .age(user.getAge())
                .build();
        // without password
    }
    private User findUserOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }
}
