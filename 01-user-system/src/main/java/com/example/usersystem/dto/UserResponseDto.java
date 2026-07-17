package com.example.usersystem.dto;

import lombok.*;
// User Entity->UserResponseDto->client, without password
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserResponseDto {
    private Long id;
    private String username;
    private String email;
    private String fullName;
    private Integer age;

}