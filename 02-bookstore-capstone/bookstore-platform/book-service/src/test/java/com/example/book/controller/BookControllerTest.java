package com.example.book.controller;

import com.example.book.config.JwtProperties;
import com.example.book.dto.BookResponseDto;
import com.example.book.exception.DuplicateResourceException;
import com.example.book.exception.GlobalExceptionHandler;
import com.example.book.exception.InsufficientStockException;
import com.example.book.exception.ResourceNotFoundException;
import com.example.book.security.JwtAuthenticationFilter;
import com.example.book.security.JwtUtil;
import com.example.book.security.SecurityConfig;
import com.example.book.security.SecurityErrorWriter;
import com.example.book.service.BookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web slice: routing, deserialization, validation, status codes, and the security rules.
 *
 * <p>{@code @WebMvcTest} loads Spring MVC and nothing else — no database, no real services. The service
 * is a {@code @MockitoBean}, so these tests are about the HTTP contract only: given that the service
 * returns X or throws Y, what does the client see?
 *
 * <p>The security classes are imported explicitly. Without them the slice runs with Spring Security's
 * defaults rather than ours, and the authorization assertions below would be testing the framework's
 * behaviour instead of this application's.
 *
 * <p>Every mutating request carries {@code with(csrf())}. The real chain disables CSRF (it is a
 * token-authenticated API — see SecurityConfig), but MockMvc's security setup applies it by default,
 * and a missing token here shows up as a 403 that looks exactly like an authorization failure.
 */
@WebMvcTest(BookController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class,
        SecurityErrorWriter.class, GlobalExceptionHandler.class})
@EnableConfigurationProperties(JwtProperties.class)
@ActiveProfiles("test")
@DisplayName("BookController (web layer)")
class BookControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private BookService bookService;

    /*
     * SecurityConfig also declares the AuthenticationManager, which needs a UserDetailsService and a
     * PasswordEncoder — both of which reach the database and have no place in a web slice. They are
     * mocked so the context can start; nothing here exercises them, because logging in is not what this
     * class is about. @WithMockUser supplies the identity directly, skipping authentication entirely so
     * the tests are about *authorization*.
     */
    @MockitoBean private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;
    @MockitoBean private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private static BookResponseDto sampleBook() {
        return new BookResponseDto(1L, "Clean Code", "9780132350884",
                new BigDecimal("42.50"), 12, null, 7L, "Robert C. Martin", Instant.EPOCH);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    @Nested
    @DisplayName("public reads")
    class PublicReads {

        @Test
        @WithAnonymousUser
        @DisplayName("GET /api/books/{id} is open to anonymous callers and serializes the DTO")
        void getIsPublic() throws Exception {
            when(bookService.findById(1L)).thenReturn(sampleBook());

            mockMvc.perform(get("/api/books/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(1))
                    .andExpect(jsonPath("$.title").value("Clean Code"))
                    .andExpect(jsonPath("$.authorName").value("Robert C. Martin"))
                    // The entity has a version column; the API contract does not expose it.
                    .andExpect(jsonPath("$.version").doesNotExist());
        }

        @Test
        @WithAnonymousUser
        @DisplayName("a missing book becomes 404 with the shared error envelope")
        void missingBookIs404() throws Exception {
            when(bookService.findById(9999L)).thenThrow(ResourceNotFoundException.book(9999L));

            mockMvc.perform(get("/api/books/9999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.error").value("Not Found"))
                    .andExpect(jsonPath("$.path").value("/api/books/9999"));
        }
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @WithAnonymousUser
        @DisplayName("anonymous POST /api/books -> 401, and the service is never reached")
        void anonymousCreateIs401() throws Exception {
            mockMvc.perform(post("/api/books").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"New","isbn":"1","price":9.99,"stock":1}"""))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.status").value(401));

            // Rejected in the filter chain — the request never becomes a method call.
            verify(bookService, never()).create(any());
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("a customer POSTing a book -> 403, not 401")
        void userCreateIs403() throws Exception {
            mockMvc.perform(post("/api/books").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"New","isbn":"1","price":9.99,"stock":1}"""))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.status").value(403));

            verify(bookService, never()).create(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("an admin POSTing a book -> 201 with a Location header")
        void adminCreateIs201() throws Exception {
            when(bookService.create(any())).thenReturn(sampleBook());

            mockMvc.perform(post("/api/books").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"Clean Code","isbn":"9780132350884","price":42.50,"stock":12}"""))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/books/1"));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("a customer deleting a book -> 403")
        void userDeleteIs403() throws Exception {
            mockMvc.perform(delete("/api/books/1").with(csrf()))
                    .andExpect(status().isForbidden());

            verify(bookService, never()).delete(any());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("an admin deleting a book -> 204 with no body")
        void adminDeleteIs204() throws Exception {
            mockMvc.perform(delete("/api/books/1").with(csrf()))
                    .andExpect(status().isNoContent());

            verify(bookService).delete(1L);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("an invalid body -> 400 naming every field that failed")
        void invalidBodyIs400() throws Exception {
            mockMvc.perform(post("/api/books").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"","price":-5,"stock":-1}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.fieldErrors.title").exists())
                    .andExpect(jsonPath("$.fieldErrors.price").exists())
                    .andExpect(jsonPath("$.fieldErrors.stock").exists());

            // Validation runs before the controller body, so the service is never invoked.
            verify(bookService, never()).create(any());
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("a non-positive purchase quantity -> 400")
        void nonPositiveQuantityIs400() throws Exception {
            mockMvc.perform(post("/api/books/1/purchase").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity":0}"""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.quantity").exists());
        }
    }

    @Nested
    @DisplayName("domain errors map to the right status")
    class DomainErrors {

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("a duplicate ISBN -> 409")
        void duplicateIsbnIs409() throws Exception {
            when(bookService.create(any()))
                    .thenThrow(new DuplicateResourceException("A book with isbn 1 already exists"));

            mockMvc.perform(post("/api/books").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"New","isbn":"1","price":9.99,"stock":1}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409));
        }

        @Test
        @WithMockUser(roles = "USER")
        @DisplayName("insufficient stock -> 409, with the numbers in the message")
        void insufficientStockIs409() throws Exception {
            when(bookService.purchase(eq(1L), eq(99), any()))
                    .thenThrow(new InsufficientStockException(1L, 99, 3));

            mockMvc.perform(post("/api/books/1/purchase").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"quantity":99}"""))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("only 3")));
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        @DisplayName("an unexpected exception -> 500 with no internal detail leaked")
        void unexpectedErrorIs500() throws Exception {
            doThrow(new IllegalStateException("connection pool exhausted at com.example.Internal:42"))
                    .when(bookService).delete(1L);

            mockMvc.perform(delete("/api/books/1").with(csrf()))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("connection pool"))));
        }
    }
}
