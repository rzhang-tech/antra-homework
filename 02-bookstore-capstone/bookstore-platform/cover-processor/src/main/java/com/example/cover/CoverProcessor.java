package com.example.cover;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns an uploaded cover into a metadata row and one email.
 *
 * <p>Triggered by S3 {@code ObjectCreated:*} on {@code covers/}. book-service does not know this
 * exists and does not wait for it — the upload returned 204 before this function was invoked, which is
 * the same decoupling Step 7 bought with Kafka, bought here with an event source instead.
 *
 * <h2>Idempotency, which is the whole point of the step</h2>
 *
 * <p><strong>S3 event delivery is at-least-once.</strong> The same {@code ObjectCreated} can arrive
 * twice, and a Lambda that failed halfway is retried automatically. Two things must survive that: the
 * DynamoDB row, and the email.
 *
 * <p>The row survives because the primary key is {@code bookId}, derived from the object key that
 * book-service chose deterministically. One book, one row, forever — a redelivery updates rather than
 * inserts, and no cleanup job is ever needed. A synthetic id per processing run would need something
 * to notice the duplicates afterwards, which is the design D21 argues against.
 *
 * <p>The email survives because of the <em>conditional</em> write. The item records which S3 object
 * <strong>version</strong> produced it, and the condition is "no row yet, or a row for a different
 * version". So:
 *
 * <ul>
 *   <li><strong>Redelivered event</strong> — same version, condition fails, nothing written and
 *       nothing sent. The function returns successfully, because a duplicate is not an error.
 *   <li><strong>Genuine re-upload</strong> — new version, condition passes, the row is updated and one
 *       email goes out. That is correct rather than a leak: an administrator who replaced a wrong
 *       cover wants to know the new one was processed.
 * </ul>
 *
 * <p>The write happens <em>before</em> the publish deliberately. Reversed, a crash between them would
 * send an email and leave no record, so the retry would send a second one — and the ordering that
 * costs at most a missing email is better than the one that costs a duplicate. It is the same
 * reasoning as Step 5d's "release before marking FAILED".
 */
public class CoverProcessor implements RequestHandler<S3Event, String> {

    /*
     * Static, and that is a Lambda idiom rather than a style choice. AWS reuses a warm container for
     * many invocations, so anything built in a static initialiser is built once per container instead
     * of once per event. For an SDK client - which resolves credentials, builds an HTTP client and
     * warms TLS - that is the difference between a 3-second cold start and a 40ms warm one.
     */
    private static final S3Client S3 = S3Client.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();

    private static final DynamoDbClient DYNAMO = DynamoDbClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();

    private static final SnsClient SNS = SnsClient.builder()
            .httpClient(UrlConnectionHttpClient.create())
            .build();

    /** Set by the deploy script. Environment variables are a Lambda's configuration. */
    private static final String TABLE = System.getenv("METADATA_TABLE");
    private static final String TOPIC_ARN = System.getenv("TOPIC_ARN");

    @Override
    public String handleRequest(S3Event event, Context context) {
        int processed = 0;
        int skipped = 0;

        for (S3EventNotificationRecord record : event.getRecords()) {
            String bucket = record.getS3().getBucket().getName();

            // S3 URL-encodes the key in the event, so "covers/1 2" arrives as "covers/1+2". Keys here
            // never contain a space, but decoding is not optional in general and the bug it causes -
            // a NoSuchKey for an object that plainly exists - is remarkably hard to see.
            String key = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8);
            String versionId = record.getS3().getObject().getVersionId();

            String bookId = CoverKey.bookIdFrom(key);
            if (bookId == null) {
                // Something under covers/ that is not covers/{number}. Not an error worth retrying:
                // throwing here would fail the whole batch and eventually fill the dead letter queue
                // with an object that will never be processable.
                context.getLogger().log("Ignoring unexpected key: " + key + "\n");
                continue;
            }

            CoverFacts facts = read(bucket, key, versionId);

            if (writeMetadata(bookId, key, versionId, facts)) {
                notifyAdmin(bookId, facts);
                processed++;
            } else {
                context.getLogger().log(
                        "Book " + bookId + " version " + versionId
                                + " already processed - no row written, no email sent\n");
                skipped++;
            }
        }

        return "processed=" + processed + " skipped=" + skipped;
    }

    private CoverFacts read(String bucket, String key, String versionId) {
        GetObjectRequest.Builder request = GetObjectRequest.builder().bucket(bucket).key(key);
        if (versionId != null && !versionId.isBlank()) {
            // Read the version the EVENT was about, not "whatever is there now". Without this, two
            // uploads in quick succession would both read the second image, and the metadata for the
            // first version would describe the second.
            request.versionId(versionId);
        }

        ResponseBytes<GetObjectResponse> object = S3.getObjectAsBytes(request.build());
        byte[] bytes = object.asByteArray();

        int width = -1;
        int height = -1;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (Exception ex) {
            // Dimensions are the one part of this that can fail on a file book-service accepted:
            // ImageIO has no WebP reader in a stock JDK, and a corrupt PNG parses to null. Recording
            // "we do not know" is better than failing the whole pipeline over a nice-to-have - the
            // size and content type are still true, and -1 is visibly not a dimension.
        }

        return new CoverFacts(
                object.response().contentLength(),
                object.response().contentType(),
                width,
                height);
    }

    /** @return true if this version was newly recorded, false if it had already been processed. */
    private boolean writeMetadata(String bookId, String key, String versionId, CoverFacts facts) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("bookId", AttributeValue.fromS(bookId));
        item.put("objectKey", AttributeValue.fromS(key));
        item.put("sizeBytes", AttributeValue.fromN(String.valueOf(facts.sizeBytes())));
        item.put("contentType", AttributeValue.fromS(
                facts.contentType() == null ? "unknown" : facts.contentType()));
        item.put("width", AttributeValue.fromN(String.valueOf(facts.width())));
        item.put("height", AttributeValue.fromN(String.valueOf(facts.height())));
        item.put("processedAt", AttributeValue.fromS(Instant.now().toString()));
        item.put("processedVersion", AttributeValue.fromS(
                versionId == null || versionId.isBlank() ? "none" : versionId));

        try {
            DYNAMO.putItem(PutItemRequest.builder()
                    .tableName(TABLE)
                    .item(item)
                    // The idempotency guard, enforced by the database rather than by a read-then-write
                    // in this function. Two concurrent invocations of the same event would both pass a
                    // check-then-act; only one can win a conditional write.
                    .conditionExpression(
                            "attribute_not_exists(bookId) OR processedVersion <> :version")
                    .expressionAttributeValues(Map.of(
                            ":version", item.get("processedVersion")))
                    .build());
            return true;

        } catch (ConditionalCheckFailedException alreadyDone) {
            return false;
        }
    }

    private void notifyAdmin(String bookId, CoverFacts facts) {
        String dimensions = facts.width() > 0
                ? facts.width() + "x" + facts.height()
                : "unknown (no reader for " + facts.contentType() + ")";

        SNS.publish(PublishRequest.builder()
                .topicArn(TOPIC_ARN)
                .subject("Cover processed for book " + bookId)
                .message("""
                        The cover for book %s has been processed.

                          size          %d bytes
                          dimensions    %s
                          content type  %s

                        This message is sent once per uploaded version. A redelivered S3 event does \
                        not produce a second one.
                        """.formatted(bookId, facts.sizeBytes(), dimensions, facts.contentType()))
                .build());
    }

    /** What the Lambda learned about the image, as opposed to what the uploader claimed. */
    record CoverFacts(long sizeBytes, String contentType, int width, int height) {
    }
}
