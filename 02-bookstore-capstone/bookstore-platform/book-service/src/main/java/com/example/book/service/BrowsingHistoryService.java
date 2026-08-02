package com.example.book.service;

import com.example.book.config.AsyncConfig;
import com.example.book.dto.BookViewDto;
import com.example.book.security.AuthenticatedUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * What a logged-in customer has looked at, in DynamoDB.
 *
 * <h2>Why this is not in PostgreSQL</h2>
 *
 * <p>book-service already owns a relational database, so the honest question is what DynamoDB adds. The
 * answer is the access pattern, not the technology:
 *
 * <ul>
 *   <li><strong>Write-heavy and unbounded.</strong> Every page view is a row. This table grows faster
 *       than the catalogue by orders of magnitude, and it grows forever until something deletes it.
 *   <li><strong>Exactly one query, ever.</strong> "The most recent N views for one user." No joins, no
 *       reporting, no ad-hoc filtering. A table with one access pattern is what a key-value store is
 *       <em>for</em>.
 *   <li><strong>Expiry is a feature.</strong> DynamoDB's TTL deletes old items at no cost. The
 *       PostgreSQL equivalent is a nightly {@code DELETE} that competes with live traffic and leaves
 *       the table bloated until it is vacuumed.
 * </ul>
 *
 * <p>Putting it in {@code bookdb} would have worked and would have grown, forever, alongside the data
 * that actually matters — with the catalogue's backups and its restore time growing to match.
 *
 * <h2>The keys, which the assignment asks to be justified</h2>
 *
 * <p><strong>Partition key {@code userId}.</strong> DynamoDB hashes it to choose a partition, and every
 * query must supply it. Many users each generate a modest independent stream, so writes spread evenly —
 * no hot partition — and it matches the only question ever asked of this table. Keying by {@code bookId}
 * would make a best-seller a hot partition (DynamoDB throttles per partition, not per table); keying by
 * a date would put every write on one partition per day, which is the hottest key possible.
 *
 * <p><strong>Sort key {@code viewedAt}, ISO-8601 UTC.</strong> Items are stored ordered within the
 * partition, so newest-first is a backwards read of an already-sorted index rather than a sort. ISO-8601
 * is chosen because its lexicographic order <em>is</em> its chronological order — a locale-formatted
 * timestamp or epoch millis as a string would not sort correctly as a string.
 */
@Service
@Slf4j
public class BrowsingHistoryService {

    /**
     * Explicitly UTC and explicitly {@code Instant}-based.
     *
     * <p>The sort key's correctness depends entirely on this: two entries written in different time
     * zones, or with an offset suffix instead of {@code Z}, would sort by their text rather than by
     * their instant. {@code Instant.toString()} is already ISO-8601 UTC, which is why nothing here
     * formats by hand.
     */
    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ISO_INSTANT;

    private final DynamoDbClient dynamo;
    private final String table;
    private final Duration retention;

    public BrowsingHistoryService(DynamoDbClient dynamo,
                                  @Value("${app.aws.dynamodb.browsing-history-table}") String table,
                                  @Value("${app.history.retention}") Duration retention) {
        this.dynamo = dynamo;
        this.table = table;
        this.retention = retention;
    }

    /**
     * Records a view, on somebody else's thread, and never makes the caller wait or fail.
     *
     * <p><strong>{@code @Async} is the whole point.</strong> A catalogue read is the most-served request
     * on the platform; adding a DynamoDB round trip to it would make every visitor pay for a feature
     * only logged-in ones use. The executor is bounded and <em>discards</em> under load — see
     * {@link AsyncConfig} for why discarding is right here and would be a defect almost anywhere else.
     *
     * <p><strong>Exceptions are swallowed, loudly.</strong> A failed history write must not turn a
     * successful catalogue read into a 500 — the customer asked for a book, they got the book. But
     * swallowing silently is how a feature stops working for a month before anyone notices, so every
     * failure is logged with the ids needed to reproduce it. {@code RuntimeException} alone, not
     * {@code DynamoDbException | RuntimeException} - the SDK's exception extends it, and a credential
     * or serialization failure would not be a DynamoDbException at all.
     *
     * <p>Note this method is public and called through the Spring proxy. Calling it from another method
     * of this class would run it synchronously and the annotation would do nothing — the same
     * proxy-based self-invocation trap {@code OrderTransactions} exists to avoid (Step 5d).
     */
    @Async(AsyncConfig.HISTORY_EXECUTOR)
    public void recordView(AuthenticatedUser viewer, Long bookId, String title) {
        if (viewer == null || viewer.id() == null) {
            // Anonymous browsing, or a token minted before `uid` existed. There is no partition key to
            // write under, and inventing one would put somebody else's views in somebody's history.
            return;
        }

        Instant now = Instant.now();

        try {
            dynamo.putItem(PutItemRequest.builder()
                    .tableName(table)
                    .item(Map.of(
                            "userId", AttributeValue.fromS(String.valueOf(viewer.id())),
                            "viewedAt", AttributeValue.fromS(ISO_UTC.format(now)),
                            "bookId", AttributeValue.fromN(String.valueOf(bookId)),
                            "title", AttributeValue.fromS(title == null ? "" : title),
                            // TTL: epoch SECONDS, as a NUMBER. Every part of that sentence is a way to
                            // get it wrong. DynamoDB ignores a TTL attribute of the wrong type without
                            // complaining, and milliseconds would set expiry to the year 33658 - in
                            // both cases the table simply grows forever and nothing reports a problem.
                            "expiresAt", AttributeValue.fromN(
                                    String.valueOf(now.plus(retention).getEpochSecond()))))
                    .build());

        } catch (RuntimeException ex) {
            log.warn("Could not record view of book {} for user {}: {}",
                    bookId, viewer.id(), ex.toString());
        }
    }

    /**
     * The caller's most recent views, newest first.
     *
     * <p>{@code scanIndexForward(false)} is what makes "newest first" free: it reads the sort key
     * backwards from the end of the partition, so the {@code limit} applies to the newest items rather
     * than to the oldest ones that then get sorted. Fetching ascending and reversing in Java would read
     * the user's <em>entire</em> history to return ten rows, and would get slower every day.
     *
     * <p><strong>TTL is not a guarantee about what a query returns.</strong> DynamoDB deletes expired
     * items on its own schedule — usually within a couple of days, not at the second they expire — and
     * until it does, an expired item is still returned by a query. The filter below is therefore not
     * belt-and-braces; it is the actual correctness boundary. Anything treating TTL as a hard cutoff is
     * relying on a background job's timing.
     */
    public List<BookViewDto> recentViews(AuthenticatedUser viewer, int limit) {
        long now = Instant.now().getEpochSecond();

        QueryResponse response = dynamo.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("userId = :uid")
                .expressionAttributeValues(Map.of(
                        ":uid", AttributeValue.fromS(String.valueOf(viewer.id())),
                        ":now", AttributeValue.fromN(String.valueOf(now))))
                // A FilterExpression is applied AFTER the read and does not reduce what is charged for.
                // Acceptable here because it removes a handful of items at the tail; it would be the
                // wrong tool for anything that filtered out most of what it read.
                .filterExpression("expiresAt > :now")
                .scanIndexForward(false)
                .limit(limit)
                .build());

        return response.items().stream()
                .map(item -> new BookViewDto(
                        Long.parseLong(item.get("bookId").n()),
                        item.containsKey("title") ? item.get("title").s() : null,
                        Instant.parse(item.get("viewedAt").s())))
                .toList();
    }
}
