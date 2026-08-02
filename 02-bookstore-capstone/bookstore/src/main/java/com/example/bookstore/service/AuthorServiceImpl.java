package com.example.bookstore.service;

import com.example.bookstore.dto.AuthorResponseDto;
import com.example.bookstore.repository.AuthorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthorServiceImpl implements AuthorService {

    private final AuthorRepository authorRepository;

    /**
     * <p>The {@code naive} branch exists on purpose. The assignment asks for an N+1 to be reproduced and
     * then fixed, and a demo is far more convincing than a claim: with SQL logging on you can hit the
     * same endpoint twice and watch the query count drop from N+1 to 1. It is reachable only via an
     * explicit {@code ?naive=true} and is documented as a teaching switch everywhere it appears.
     *
     * <p>In production code this branch would not exist — you would keep only the fetch-join path.
     */
    @Override
    @Transactional(readOnly = true)
    public List<AuthorResponseDto> findAllWithBooks(boolean naive) {
        // Both paths order explicitly. Without an ORDER BY the database may return rows in any order it
        // finds convenient, and these two queries genuinely did differ — the fetch join came back in a
        // different order than findAll(). Same data, different sequence, which is still a broken API
        // contract and an unstable client experience.
        var authors = naive
                // findAll() loads authors only. AuthorResponseDto.from then touches author.getBooks(),
                // and each of those touches fires its own SELECT. 1 + N.
                ? authorRepository.findAll(Sort.by(Sort.Direction.ASC, "id"))
                // One SELECT with a LEFT JOIN FETCH: authors and books arrive together.
                : authorRepository.findAllWithBooks();

        return authors.stream().map(AuthorResponseDto::from).toList();
    }
}
