package com.example.book.service;

import com.example.book.dto.AuthorResponseDto;

import java.util.List;

public interface AuthorService {

    /**
     * Every author with their books, loaded in one query.
     *
     * @param naive when true, deliberately takes the N+1 path instead. A teaching switch, not a
     *              feature — see {@link AuthorServiceImpl} for why it is here.
     */
    List<AuthorResponseDto> findAllWithBooks(boolean naive);
}
