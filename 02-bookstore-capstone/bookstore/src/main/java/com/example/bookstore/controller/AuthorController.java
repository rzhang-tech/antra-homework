package com.example.bookstore.controller;

import com.example.bookstore.dto.AuthorResponseDto;
import com.example.bookstore.service.AuthorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/authors")
@RequiredArgsConstructor
public class AuthorController {

    private final AuthorService authorService;

    /**
     * List every author with their books.
     *
     * @param naive {@code ?naive=true} takes the N+1 path instead of the fetch join. A demonstration
     *              switch for Step 2c — with {@code show-sql} on, the two produce visibly different
     *              query counts against identical data.
     */
    @GetMapping
    public List<AuthorResponseDto> list(@RequestParam(defaultValue = "false") boolean naive) {
        return authorService.findAllWithBooks(naive);
    }
}
