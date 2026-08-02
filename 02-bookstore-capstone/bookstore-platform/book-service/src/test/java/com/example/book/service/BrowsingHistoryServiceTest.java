package com.example.book.service;

import com.example.book.dto.BookViewDto;
import com.example.book.security.AuthenticatedUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The shape of what goes into DynamoDB, and the shape of what is asked for.
 *
 * <p>Mocked rather than run against a real table, and the reason is what these tests are about. Every
 * defect available here is a defect in the <em>request</em> — a TTL in the wrong unit, a sort key that
 * does not sort, a query that reads the oldest items instead of the newest. DynamoDB accepts all three
 * without complaint: the wrong TTL type is silently ignored, an unsortable sort key sorts wrongly, and
 * an ascending query returns a perfectly valid page of the wrong data. A real table would go green on
 * every one of them.
 *
 * <p>What this cannot check is that the table exists with these keys. That is asserted against real AWS
 * in the Step 9a section of the README, and by the provisioning script being the only way the table is
 * created.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowsingHistory")
class BrowsingHistoryServiceTest {

    private static final String TABLE = "UserBrowsingHistory-test";
    private static final AuthenticatedUser SHOPPER = new AuthenticatedUser(7L, "shopper", "USER");

    @Mock
    private DynamoDbClient dynamo;

    private BrowsingHistoryService service() {
        return new BrowsingHistoryService(dynamo, TABLE, Duration.ofDays(30));
    }

    @Nested
    @DisplayName("recording a view")
    class Recording {

        @Test
        @DisplayName("writes the TTL as epoch SECONDS in a NUMBER attribute")
        void ttlIsSecondsNotMillis() {
            Instant before = Instant.now();

            service().recordView(SHOPPER, 42L, "Clean Code");

            Map<String, AttributeValue> item = capturedItem();

            // The single most common way to get DynamoDB TTL wrong, and it fails silently: DynamoDB
            // ignores a TTL attribute that is not a Number, and treats a Number as SECONDS. Write
            // millis and every item expires in the year 33658 - the table grows forever and nothing
            // anywhere reports a problem.
            AttributeValue ttl = item.get("expiresAt");
            assertThat(ttl.n()).as("expiresAt must be a NUMBER").isNotNull();
            assertThat(ttl.s()).as("expiresAt must not be a STRING").isNull();

            long expiresAt = Long.parseLong(ttl.n());
            long expected = before.plus(Duration.ofDays(30)).getEpochSecond();
            assertThat(expiresAt).isBetween(expected - 5, expected + 5);

            // Ten digits, not thirteen. Stated as its own assertion because "is it seconds?" is the
            // question, and a range check would pass for millis if the expected value were also millis.
            assertThat(String.valueOf(expiresAt)).hasSize(10);
        }

        @Test
        @DisplayName("the sort key is an ISO-8601 UTC string, so lexicographic order is chronological")
        void sortKeySortsCorrectlyAsAString() {
            service().recordView(SHOPPER, 42L, "Clean Code");

            String viewedAt = capturedItem().get("viewedAt").s();

            assertThat(viewedAt).endsWith("Z");
            assertThatCode(() -> Instant.parse(viewedAt)).doesNotThrowAnyException();

            // The property the whole design rests on: DynamoDB orders sort keys as strings, so the
            // format has to make string order and time order the same thing. A local timestamp, an
            // offset like +05:00, or epoch millis as a string would each break it - and the symptom
            // would be a "recently viewed" list in a plausible but wrong order.
            assertThat("2026-08-02T09:00:00Z").isLessThan("2026-08-02T18:05:47Z");
        }

        @Test
        @DisplayName("partition key is the id from the token, as a string")
        void partitionKeyIsTheUserId() {
            service().recordView(SHOPPER, 42L, "Clean Code");

            Map<String, AttributeValue> item = capturedItem();
            assertThat(item.get("userId").s()).isEqualTo("7");
            assertThat(item.get("bookId").n()).isEqualTo("42");
            assertThat(item.get("title").s()).isEqualTo("Clean Code");
        }

        @Test
        @DisplayName("an anonymous visitor writes nothing at all")
        void anonymousBrowsingIsNotRecorded() {
            service().recordView(null, 42L, "Clean Code");
            verify(dynamo, never()).putItem(any(PutItemRequest.class));
        }

        @Test
        @DisplayName("a token with no uid writes nothing rather than inventing a key")
        void aTokenWithoutAUserIdIsNotRecorded() {
            // A service token, or one minted before Step 5b added `uid`. There is no partition key to
            // write under, and defaulting to something would file one person's views under another's.
            service().recordView(new AuthenticatedUser(null, "service:payment", "ADMIN"), 42L, "x");
            verify(dynamo, never()).putItem(any(PutItemRequest.class));
        }

        @Test
        @DisplayName("a DynamoDB failure never reaches the customer reading a book")
        void failuresAreSwallowed() {
            when(dynamo.putItem(any(PutItemRequest.class)))
                    .thenThrow(DynamoDbException.builder().message("throughput exceeded").build());

            // The catalogue read has already produced its answer by the time this runs. Turning a
            // history failure into a 500 would mean the customer did not get the book they asked for
            // because a nice-to-have list could not be updated.
            assertThatCode(() -> service().recordView(SHOPPER, 42L, "Clean Code"))
                    .doesNotThrowAnyException();
        }

        private Map<String, AttributeValue> capturedItem() {
            ArgumentCaptor<PutItemRequest> captor = ArgumentCaptor.forClass(PutItemRequest.class);
            verify(dynamo).putItem(captor.capture());
            assertThat(captor.getValue().tableName()).isEqualTo(TABLE);
            return captor.getValue().item();
        }
    }

    @Nested
    @DisplayName("reading history")
    class Reading {

        @Test
        @DisplayName("queries backwards, so the limit applies to the NEWEST items")
        void readsNewestFirstFromTheIndex() {
            when(dynamo.query(any(QueryRequest.class)))
                    .thenReturn(QueryResponse.builder().items(List.of()).build());

            service().recentViews(SHOPPER, 10);

            ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(dynamo).query(captor.capture());
            QueryRequest request = captor.getValue();

            // FALSE is the entire feature. Ascending plus a limit returns the user's ten OLDEST views;
            // ascending without a limit reads their whole history to show ten rows, and gets slower
            // every day they use the site. Neither would fail a test that only checked the output was
            // sorted - which is why this asserts the request rather than the result.
            assertThat(request.scanIndexForward()).isFalse();
            assertThat(request.limit()).isEqualTo(10);
            assertThat(request.keyConditionExpression()).isEqualTo("userId = :uid");
            assertThat(request.expressionAttributeValues().get(":uid").s()).isEqualTo("7");
        }

        @Test
        @DisplayName("filters out expired items, because TTL deletion is not immediate")
        void doesNotTrustTtlToHaveDeletedAnything() {
            when(dynamo.query(any(QueryRequest.class)))
                    .thenReturn(QueryResponse.builder().items(List.of()).build());

            service().recentViews(SHOPPER, 10);

            ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
            verify(dynamo).query(captor.capture());

            // DynamoDB removes expired items on its own schedule - typically within 48 hours, not at
            // the second they expire - and RETURNS THEM FROM QUERIES until it does. Without this
            // filter, "last 30 days" would quietly mean "last 30-ish days, depending on when a
            // background job last ran", which is not a promise anyone should make in a UI.
            assertThat(captor.getValue().filterExpression()).isEqualTo("expiresAt > :now");
        }

        @Test
        @DisplayName("maps an item back to a view, keeping the title captured at the time")
        void mapsItemsToDto() {
            when(dynamo.query(any(QueryRequest.class))).thenReturn(QueryResponse.builder()
                    .items(List.of(Map.of(
                            "bookId", AttributeValue.fromN("42"),
                            "title", AttributeValue.fromS("Clean Code"),
                            "viewedAt", AttributeValue.fromS("2026-08-02T18:05:47Z"))))
                    .build());

            List<BookViewDto> views = service().recentViews(SHOPPER, 10);

            assertThat(views).singleElement().satisfies(view -> {
                assertThat(view.bookId()).isEqualTo(42L);
                assertThat(view.title()).isEqualTo("Clean Code");
                assertThat(view.viewedAt()).isEqualTo(Instant.parse("2026-08-02T18:05:47Z"));
            });
        }
    }
}
