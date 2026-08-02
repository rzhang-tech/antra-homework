package com.example.bookstore.service;

import com.example.bookstore.dto.BookRequestDto;
import com.example.bookstore.dto.BookResponseDto;
import com.example.bookstore.dto.PageResponseDto;
import org.springframework.data.domain.Pageable;

/**
 * Business operations on the catalog.
 *
 * <p>An interface rather than a bare class so the controller depends on <em>what</em> can be done, not
 * on how it is done. That is what makes {@code BookServiceImpl} replaceable in a unit test and what
 * lets Spring wrap the bean in a proxy for {@code @Transactional} and the logging aspect.
 */
public interface BookService {

    PageResponseDto<BookResponseDto> findAll(String keyword, Pageable pageable);

    BookResponseDto findById(Long id);

    BookResponseDto create(BookRequestDto request);

    BookResponseDto update(Long id, BookRequestDto request);

    /**
     * Sell {@code quantity} copies, decrementing stock.
     *
     * <p>The first multi-step write in the project: read the current stock, check it, write the new
     * value. Between the read and the write another transaction can do exactly the same thing — which
     * is what {@code @Version} on {@link com.example.bookstore.entity.Book} exists to catch.
     */
    BookResponseDto purchase(Long id, int quantity);

    void delete(Long id);
}
