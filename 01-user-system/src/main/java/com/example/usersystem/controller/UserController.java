package com.example.usersystem.controller;

import com.example.usersystem.dto.UserRequestDto;
import com.example.usersystem.dto.UserResponseDto;
import com.example.usersystem.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController                       // 收 HTTP、返 JSON 的控制器
@RequestMapping("/api/users")         // 这个类下所有 URL 都以 /api/users 开头
@RequiredArgsConstructor              // 构造器注入
public class UserController {

    private final UserService userService;   // 注入 Service

    // new  POST /api/users
    @PostMapping
    public ResponseEntity<UserResponseDto> createUser(@Valid @RequestBody UserRequestDto request) {
        UserResponseDto created = userService.createUser(request);
        URI location = URI.create("/api/users/" + created.getId());
        return ResponseEntity.created(location).body(created);   // 201 Created
    }

    // get all  GET /api/users
    @GetMapping
    public ResponseEntity<List<UserResponseDto>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());     // 200 OK
    }

    // get 1  GET /api/users/{id}
    @GetMapping("/{id}")
    public ResponseEntity<UserResponseDto> getUserById(@PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));   // 200 OK
    }

    // search  GET /api/users/search?keyword=xxx
    @GetMapping("/search")
    public ResponseEntity<List<UserResponseDto>> searchUsers(@RequestParam String keyword) {
        return ResponseEntity.ok(userService.searchByUsername(keyword));
    }

    //  update  PUT /api/users/{id}
    @PutMapping("/{id}")
    public ResponseEntity<UserResponseDto> updateUser(
            @PathVariable Long id, @Valid @RequestBody UserRequestDto request) {
        return ResponseEntity.ok(userService.updateUser(id, request));
    }

    // del  DELETE /api/users/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();               // 204 No Content
    }
}
