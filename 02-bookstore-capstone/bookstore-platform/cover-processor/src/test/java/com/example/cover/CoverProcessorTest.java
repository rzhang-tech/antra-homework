package com.example.cover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract between book-service and this function, which no compiler checks.
 *
 * <p>{@code CoverStorageService.keyFor} builds {@code covers/{bookId}} in one repository's module and
 * {@code bookIdFrom} takes it apart in another. They share no code — deliberately, since a Lambda that
 * depended on a Spring service's jar would be a Lambda that had to be redeployed when that service
 * changed. What they share is a string format, and a string format with two independent
 * implementations is exactly the thing that drifts.
 *
 * <p>If this test and {@code CoverStorageServiceTest.keyIsDeterministic} ever disagree, the pipeline
 * stops silently: the object lands, the event fires, the function finds no book id and ignores it, and
 * the only symptom is a cover that never gets metadata or an email.
 */
@DisplayName("Deriving the book id from the object key")
class CoverProcessorTest {

    @Test
    @DisplayName("reverses the key book-service writes")
    void parsesTheDeterministicKey() {
        // Must match CoverStorageService.keyFor(42L) exactly.
        assertThat(CoverProcessor.bookIdFrom("covers/42")).isEqualTo("42");
        assertThat(CoverProcessor.bookIdFrom("covers/1")).isEqualTo("1");
    }

    @Test
    @DisplayName("ignores anything that is not a cover, rather than failing the batch")
    void ignoresUnexpectedKeys() {
        // Returning null rather than throwing is the important half. Throwing would fail the whole
        // invocation, Lambda would retry it, and the retry would fail identically - so one stray
        // object under covers/ would fill the dead letter queue with something that can never
        // succeed, while the covers behind it waited.
        assertThat(CoverProcessor.bookIdFrom("covers/not-a-number")).isNull();
        assertThat(CoverProcessor.bookIdFrom("covers/42.png")).isNull();
        assertThat(CoverProcessor.bookIdFrom("thumbnails/42")).isNull();
        assertThat(CoverProcessor.bookIdFrom("covers/")).isNull();
        assertThat(CoverProcessor.bookIdFrom(null)).isNull();
    }

    @Test
    @DisplayName("an extension would break it, which is why the key has none")
    void rejectsKeysWithExtensions() {
        // Step 9b's decision, asserted from the other side: had book-service used covers/{id}.png,
        // this function would have needed to strip an extension it cannot predict - and uploading a
        // JPEG over a PNG would have left two objects, two events and two rows for one book.
        assertThat(CoverProcessor.bookIdFrom("covers/42.jpg")).isNull();
    }
}
