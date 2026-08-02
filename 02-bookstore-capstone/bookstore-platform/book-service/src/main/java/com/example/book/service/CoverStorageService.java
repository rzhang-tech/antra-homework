package com.example.book.service;

import com.example.book.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Cover images in S3.
 *
 * <h2>The object key is deterministic, and that is the load-bearing decision</h2>
 *
 * <p>{@code covers/{bookId}} — no UUID, no timestamp, no file extension. Everything else in Step 9
 * rests on it:
 *
 * <ul>
 *   <li><strong>Re-uploading replaces.</strong> A random key per upload would leave the old object
 *       behind, so a book with three cover revisions would own three objects, and "the cover of book
 *       42" would need a lookup table to answer.
 *   <li><strong>Step 9c's idempotency comes free.</strong> The Lambda derives the {@code CoverMetadata}
 *       primary key from this key, so a redelivered S3 event and a genuine re-upload both write the
 *       same row rather than a second one. An idempotency key that has to be generated and carried is
 *       an idempotency key somebody eventually forgets (D21).
 *   <li><strong>No extension</strong> because {@code covers/42.png} and {@code covers/42.jpg} are two
 *       objects, and uploading a JPEG over a PNG would leave both. The content type travels as S3
 *       object metadata instead, which is where a content type belongs.
 * </ul>
 *
 * <p>The cost, stated: S3 versioning is enabled on the bucket, so a replaced cover is recoverable. A
 * deterministic key without versioning would make a wrong upload destructive.
 *
 * <h2>Upload streams through this service; download does not</h2>
 *
 * <p>They are deliberately asymmetric.
 *
 * <p><strong>Upload</strong> goes through here because the bytes have to be checked before they are
 * accepted: the caller must be an ADMIN, the content type must be an image, and the size must be
 * bounded. A presigned PUT URL — the scalable alternative — hands the client a URL that bypasses every
 * one of those, and validation then has to happen after the object has already landed. At this volume
 * the bandwidth is free and the validation is the point.
 *
 * <p><strong>Download</strong> is a redirect to a presigned GET, because streaming an image through a
 * Java service costs a thread and the service's bandwidth for the whole transfer — precisely the
 * workload S3 exists to take off you. Presigning is <em>local</em> crypto: no call to AWS, just an
 * HMAC over the request. The service does microseconds of work and S3 does the megabytes.
 *
 * <p>What a presigned URL gives up: once issued it works for anyone holding it until it expires.
 * Acceptable here because the requirement makes cover retrieval public anyway — the URL discloses
 * nothing that a caller could not already fetch. It would need considerably more thought for a
 * private object, and "it is only a signed URL" is how private objects end up in a chat log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoverStorageService {

    /**
     * Checked against the browser-supplied content type AND enforced by the size limit in config.
     *
     * <p>A content type is a claim by the client, not a fact. This list stops the honest mistakes —
     * uploading a PDF, or a 4 GB video — and stops nothing determined: a renamed executable with
     * {@code Content-Type: image/png} passes. What makes that survivable is that the bucket is private,
     * nothing executes what it stores, and 9c's Lambda parses the object as an image and fails loudly
     * if it is not one. Defence in depth, with this layer honest about being the shallow one.
     */
    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final S3Client s3;
    private final S3Presigner presigner;
    private final BookService bookService;

    @Value("${app.aws.s3.covers-bucket}")
    private String bucket;

    @Value("${app.aws.s3.presign-ttl}")
    private Duration presignTtl;

    /** {@code covers/42}. Public and static because the Lambda in 9c has to reverse it. */
    public static String keyFor(Long bookId) {
        return "covers/" + bookId;
    }

    /**
     * Stores a cover, replacing whatever was there.
     *
     * <p>Verifies the book exists first. Uploading a cover for book 999 would otherwise leave an
     * orphaned object nothing references and 9c would email an admin about a book that is not there.
     */
    public void upload(Long bookId, MultipartFile file) throws IOException {
        bookService.findById(bookId);   // 404s if it does not exist

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Cover must be one of " + ALLOWED_TYPES + ", got " + contentType);
        }
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Cover file is empty");
        }

        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(keyFor(bookId))
                        .contentType(contentType)
                        // Metadata rather than a database column. The Lambda reads it in 9c, and
                        // keeping it on the object means the object is self-describing - anyone with
                        // the bucket can tell what a cover belongs to without book-service's help.
                        .metadata(Map.of("book-id", String.valueOf(bookId)))
                        .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

        log.info("Stored cover for book {} as s3://{}/{} ({} bytes, {})",
                bookId, bucket, keyFor(bookId), file.getSize(), contentType);
    }

    /**
     * A short-lived URL the client fetches directly from S3.
     *
     * <p>{@code headObject} first, so a missing cover is a 404 from this service rather than a redirect
     * to a URL that will 404 from S3. The difference matters to a client: one is "no cover", the other
     * looks like a broken integration.
     */
    public URL presignedUrl(Long bookId) {
        String key = keyFor(bookId);

        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException ex) {
            throw new ResourceNotFoundException("No cover for book " + bookId);
        }

        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(presignTtl)
                        .getObjectRequest(r -> r.bucket(bucket).key(key))
                        .build())
                .url();
    }
}
