package com.example.usersystem.exception;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;   // error timr

    private int status;                // HTTP error status like 404
    private String error;              //
    private String message;            //
    private String path;               //
    private List<String> validationErrors;  //
}