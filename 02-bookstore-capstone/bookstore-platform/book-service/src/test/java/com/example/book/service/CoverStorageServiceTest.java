package com.example.book.service;

import com.example.book.dto.BookResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The object key, and what is refused before anything reaches S3.
 *
 * <p>Mocked, because the interesting properties are all about the <em>request</em> and S3 would accept
 * every one of the mistakes: a random key is a perfectly valid key, and a text file stored as a cover
 * is a perfectly valid object. The round trip against real S3 is demonstrated in the Step 9b section of
 * the README — this is the part that has to keep being true.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Cover storage")
class CoverStorageServiceTest {

    @Mock private S3Client s3;
    @Mock private S3Presigner presigner;
    @Mock private BookService bookService;

    private CoverStorageService service() {
        CoverStorageService service = new CoverStorageService(s3, presigner, bookService);
        ReflectionTestUtils.setField(service, "bucket", "covers-test");
        ReflectionTestUtils.setField(service, "presignTtl", Duration.ofMinutes(5));
        return service;
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("file", "cover.png", "image/png", new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("the key is derived from the book id alone, with no extension")
    void keyIsDeterministic() {
        // Everything else in Step 9 rests on this. A UUID or a timestamp in the key would leave the
        // previous cover behind on every re-upload, and would break 9c's idempotency: the Lambda
        // derives its DynamoDB key from this string, so a re-upload has to produce the SAME key or it
        // writes a second CoverMetadata row and sends a second email.
        assertThat(CoverStorageService.keyFor(42L)).isEqualTo("covers/42");
        assertThat(CoverStorageService.keyFor(42L)).isEqualTo(CoverStorageService.keyFor(42L));

        // No extension, so uploading a JPEG over a PNG replaces rather than leaving covers/42.png
        // AND covers/42.jpg with nothing to say which is current.
        assertThat(CoverStorageService.keyFor(42L)).doesNotContain(".");
    }

    @Nested
    @DisplayName("uploading")
    class Uploading {

        @Test
        @DisplayName("stores under the deterministic key, with the content type as object metadata")
        void storesTheObject() throws IOException {
            when(bookService.findById(42L)).thenReturn(any(BookResponseDto.class));

            service().upload(42L, png());

            ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3).putObject(captor.capture(), any(RequestBody.class));

            PutObjectRequest request = captor.getValue();
            assertThat(request.bucket()).isEqualTo("covers-test");
            assertThat(request.key()).isEqualTo("covers/42");
            assertThat(request.contentType()).isEqualTo("image/png");

            // On the object, not in a database column: the object is then self-describing, and the
            // Lambda in 9c can tell what a cover belongs to without asking book-service.
            assertThat(request.metadata()).containsEntry("book-id", "42");
        }

        @Test
        @DisplayName("a non-image is refused before S3 is touched")
        void refusesNonImages() {
            assertThatThrownBy(() -> service().upload(42L,
                    new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("text/plain");

            // Rejected BEFORE the put, not cleaned up after it. And it is an IllegalArgumentException
            // rather than something ad hoc because GlobalExceptionHandler maps that to 400 - this
            // returned 500 when first written, which is the server claiming a bug it does not have.
            verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("a cover for a book that does not exist is a 404, not an orphaned object")
        void refusesUnknownBooks() {
            when(bookService.findById(9999L))
                    .thenThrow(new com.example.book.exception.ResourceNotFoundException("nope"));

            assertThatThrownBy(() -> service().upload(9999L, png()))
                    .isInstanceOf(com.example.book.exception.ResourceNotFoundException.class);

            // Without the check, S3 would hold an object nothing references, and 9c would email an
            // administrator about a book that is not in the catalogue.
            verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("an empty file is refused")
        void refusesEmptyFiles() {
            assertThatThrownBy(() -> service().upload(42L,
                    new MockMultipartFile("file", "cover.png", "image/png", new byte[0])))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
