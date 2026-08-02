package com.example.bookstore.service;

import com.example.bookstore.dto.BookRequestDto;
import com.example.bookstore.dto.BookResponseDto;
import com.example.bookstore.dto.PageResponseDto;
import com.example.bookstore.entity.Book;
import com.example.bookstore.exception.DuplicateResourceException;
import com.example.bookstore.exception.ResourceNotFoundException;
import com.example.bookstore.repository.BookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class BookServiceImpl implements BookService {

    /**
     * Constructor injection (via Lombok's {@code @RequiredArgsConstructor} on a final field) rather than
     * {@code @Autowired} on the field: the dependency is immutable, impossible to forget, and can be
     * passed in directly by a unit test with no Spring context.
     */
    private final BookRepository bookRepository;

    @Override
    @Transactional(readOnly = true)
    public PageResponseDto<BookResponseDto> findAll(String keyword, Pageable pageable) {
        Page<Book> books = StringUtils.hasText(keyword)
                ? bookRepository.findByTitleContainingIgnoreCase(keyword.trim(), pageable)
                : bookRepository.findAll(pageable);
        return PageResponseDto.from(books, BookResponseDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public BookResponseDto findById(Long id) {
        return BookResponseDto.from(getOrThrow(id));
    }

    @Override
    @Transactional
    public BookResponseDto create(BookRequestDto request) {
        if (StringUtils.hasText(request.isbn()) && bookRepository.existsByIsbn(request.isbn())) {
            throw new DuplicateResourceException("A book with isbn " + request.isbn() + " already exists");
        }
        Book book = Book.builder()
                .title(request.title())
                .isbn(request.isbn())
                .price(request.price())
                .stock(request.stock())
                .build();
        return BookResponseDto.from(bookRepository.save(book));
    }

    @Override
    @Transactional
    public BookResponseDto update(Long id, BookRequestDto request) {
        Book book = getOrThrow(id);
        if (StringUtils.hasText(request.isbn())
                && bookRepository.existsByIsbnAndIdNot(request.isbn(), id)) {
            throw new DuplicateResourceException("A book with isbn " + request.isbn() + " already exists");
        }
        book.setTitle(request.title());
        book.setIsbn(request.isbn());
        book.setPrice(request.price());
        book.setStock(request.stock());
        // No explicit save() call: `book` is a managed entity inside this transaction, so Hibernate
        // flushes the changes on commit (dirty checking).
        return BookResponseDto.from(book);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        bookRepository.delete(getOrThrow(id));
    }

    private Book getOrThrow(Long id) {
        return bookRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.book(id));
    }
}
