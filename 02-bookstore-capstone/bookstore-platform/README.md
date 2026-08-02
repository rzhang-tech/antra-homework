# bookstore-platform — the services (Steps 5–11)

The monolith, split. It is preserved in Git at the `step-4-monolith` tag:

```bash
git checkout step-4-monolith
```

## Status

| Service | Port | Database | State |
|---------|------|----------|-------|
| `user-service` | 8081 | `userdb` on 5433 | ☑ 5a |
| `book-service` | 8082 | `bookdb` on 5434 | ☑ 5a |
| `order-service` | 8083 | `orderdb` on 5435 | ☑ 5b |
| `payment-service` | 8084 | `paymentdb` on 5436 | ☑ 5e |

## Run it

```bash
docker compose up -d
```

(from `02-bookstore-capstone/` — starts **four** PostgreSQL instances)

```bash
cd user-service && ../mvnw spring-boot:run
```

```bash
cd book-service && ../mvnw spring-boot:run
```

```bash
cd order-service && ../mvnw spring-boot:run
```

```bash
cd payment-service && ../mvnw spring-boot:run
```

Then work through [test-platform.http](test-platform.http), which exercises the boundary itself: a token
minted on 8081, accepted on 8082.

Everything at once:

```bash
./mvnw test
```

94 tests across the four services. Testcontainers supplies the databases, so nothing needs to be running.

---

# Step 5a — the split

## What actually changed

The monolith was one process, one database, one deployable. It is now two of each. The Java is largely
the same code in different packages; the interesting changes are the ones the split *forced*.

### Two databases, and the isolation is real

Not one server with two schemas — two PostgreSQL instances, separate credentials, separate ports. The
difference is testable:

```
$ psql bookdb -c "SELECT * FROM users"
ERROR:  relation "users" does not exist

$ psql "postgresql://bookdb:bookdb@user-db:5432/userdb"
FATAL:  password authentication failed for user "bookdb"
```

book-service cannot read a user by accident, because it has neither the table nor the credentials. A
shared server with two schemas would look identical in a diagram and enforce nothing.

### Each service's schema starts at V1

The monolith's migration history reached V5 — create, add author, add indexes, fix the version default.
A new database has no history to replay, so each service's `V1__init.sql` is written as the schema
*should* be rather than as it *became*, including the `version BIGINT NOT NULL DEFAULT 0` that took the
monolith until V5 to get right. The old migrations remain at the tag.

### book-service can verify tokens but cannot issue them

`CustomUserDetailsService` is gone from it, along with the `AuthenticationManager` bean and
`JwtUtil.generate`. It has no login endpoint, no users table, and no way to check a password. It holds
the signing key solely to verify signatures.

Deleting `generate()` rather than leaving it unused is deliberate: two services able to mint credentials
is two places to audit, and an unused method is an invitation.

### Roles cross the boundary as strings, not as an enum

The copied `JwtUtil` returned `Role.valueOf(claim)`, which does not compile in book-service — `Role` is
user-service's type. The tempting fix is to copy the enum, or to publish it in a shared jar. Both are
wrong in the same way: user-service adds a role tomorrow, and book-service starts throwing
`IllegalArgumentException` **inside its security filter**.

`roleOf` returns the raw string. An unrecognised role matches no rule and becomes a 403 — the safe
outcome, and a decision book-service is entitled to make on its own. There is a test for exactly this
(`unknownRoleIsRefusedNotFatal`).

### The compiler found a misplaced concern

`user-service` would not build: its copied `GlobalExceptionHandler` still handled
`InsufficientStockException`. Stock is a catalog concept, and this service has no books and no reason to
know the word. In the monolith that handler sat in a shared class where nothing objected. The split made
every exception belong to exactly one service, and the compiler enforced it.

### The integration test had to be rewritten

`PurchaseFlowIntegrationTest` used to start with `POST /api/auth/register`. That endpoint is now in
another process — so a test of book-service would fail whenever user-service happened to be down, which
is not a test of book-service.

It now mints its own token with the shared key. Not a workaround: book-service never verified tokens by
*asking* user-service, it verifies a signature. Anything holding the key can produce an acceptable
token, and that is the contract under test. The real login-then-call path is covered in
`test-platform.http` and, from Step 8, by the gateway.

`user-service` gained the other half — `AuthFlowIntegrationTest` — including a test that parses its own
issued token with the shared key, which is the exact operation every other service performs.

## What got worse

Worth stating plainly, because this is the part a microservices tutorial usually skips:

- **Two processes and two databases to run a bookstore.** Four by the end of Step 5. This is a real cost,
  and Step 10 exists because of it.
- **"What is public across the platform?" is no longer answerable from one file.** Each service owns its
  own rules. Step 8's gateway restores a single place to ask.
- **Nothing tests the platform end to end automatically any more.** The per-service tests are honest
  about their scope, but a manual `test-platform.http` run is the only thing currently checking that a
  token from 8081 works on 8082.
- **The signing key is now shared configuration.** Two services must agree on it, and today that means
  the same literal in two files. Step 6 is the answer.

None of these were problems in the monolith. They are the price of independent deployability, and the
next steps are largely about paying it down.

---

# Step 5b — order-service, and the first cross-service call

`POST /api/orders` is the first request on this platform that cannot be served by one service. Placing
an order needs a book's price and stock, and those live in another process now.

## What a method call became

In the monolith this was `bookService.findById(id)`: it could not fail, returned in microseconds, and
shared a transaction with its caller. The Feign version looks almost identical at the call site — which
is exactly what makes distributed systems deceptive. It can now be slow, time out, return 500, or find
nobody listening, and none of that is visible in the syntax.

```java
@FeignClient(name = "book-service", url = "${app.book-service.url}")
public interface BookClient {
    @GetMapping("/api/books/{id}")
    BookSnapshot findById(@PathVariable("id") Long id);
}
```

## Identity has to be carried by hand

Without `FeignAuthPropagation`, order-service authenticates the customer perfectly and then calls
book-service **anonymously** — so the purchase comes back 401 and ordering fails for a reason that has
nothing to do with orders. The monolith never had to think about this because there was no hop.

The token is forwarded rather than a service account used, because book-service's rules are written
about *people*: a customer may purchase, only an admin may edit the catalog. A service identity would
mean either giving order-service broader permissions than any of its callers — a confused deputy — or
duplicating user-facing rules into a second policy. One set of rules, evaluated against the real actor.

The cost is real: the downstream call inherits the token's lifetime, and forwarding is only safe inside
one trust boundary. A token must never be passed to a third party.

## Explicit timeouts

```yaml
connect-timeout: 2000
read-timeout: 3000
```

Feign's defaults are effectively "wait forever". An unbounded call is how one slow service exhausts
every caller's threads and takes the platform down with it. These numbers are a promise about how long
order-service is willing to be blocked — not a guess at how fast the catalog is.

## Prices and titles are captured, not referenced

`order_item` stores `book_title` and `unit_price` as they were when the order was placed. Two reasons,
and both showed up in testing:

- **Correctness.** A price change must not alter what a past customer was charged.
- **Availability.** With book-service stopped, `GET /api/orders` still returns full order history,
  titles and prices included. A version that looked prices up on read would have gone down with the
  catalog.

## The ordering of a place-order

1. **Read every book and validate.** Free and reversible; rejects bad orders before anything changes.
2. **Reserve stock, item by item.** The first call with consequences.
3. **Write the order locally.** Last, because it is the only step this service can roll back.

`@Transactional` on that method now covers *only this service's rows*. It has no authority over anything
book-service committed. That is the honest statement of the problem 5d solves.

## Two bugs this step produced

**An `ErrorDecoder` only sees HTTP responses.** With book-service stopped, placing an order returned
**500** — the carefully-written status mapping never ran, because Feign throws before any decoder when
there is no response at all. A `FeignException` handler now maps transport failures to **503**, which is
the true statement: the request was fine, the catalog is down. A 500 blames the caller and sends someone
debugging the wrong service.

**A `@Configuration` class cannot share a name with its own `@Bean` method.** `BookClientErrorDecoder`
registered a bean named after the class *and* a bean named after the method, and the context refused to
start. Renaming it `BookClientErrorConfig` fixed it and is a better name anyway — it is configuration
that produces a decoder, not a decoder.

## What still fails badly

With book-service stopped, every order attempt still travels the full path and pays the timeout before
failing. Under load that means every request thread parked for seconds waiting on a service already
known to be down — the classic cascading failure. 5c makes it fail fast.

---

# Step 5c — Resilience4j: failing fast

5b left ordering correct but slow to fail. With book-service down, every attempt travelled the full path
and paid the timeout before giving up — so under load every request thread parks for seconds waiting on
a service already known to be dead. That is how one service's outage becomes everybody's.

## Measured, on the running platform

`book-service` stopped, orders placed one after another:

```
attempt 1   503   761 ms   CLOSED     buffered=3
attempt 2   503   727 ms   CLOSED     buffered=4
attempt 3   503   755 ms   OPEN       failureRate=60%
attempt 4   503    85 ms   OPEN
attempt 5   503    87 ms   OPEN
attempt 6   503    79 ms   OPEN
```

**761 ms to 85 ms.** The circuit opened once five calls had accumulated (`minimum-number-of-calls`) and
60% of them had failed. After that no request leaves the process at all — the remaining 85 ms is
order-service's own overhead, not waiting on the catalog.

Then book-service was restarted:

```
                     OPEN        still refusing; the 10s cooldown has not elapsed
  (wait 11s)         HALF_OPEN   three probes admitted
  201                CLOSED      probes passed; normal service resumed
```

The whole state machine, from `/actuator/circuitbreakers`, without touching a config file.

## Two policies, because the two calls are not alike

`BookClient` says *what* the HTTP calls are. `CatalogGateway` decides *how they may fail* — which has to
be per-operation, and a policy on the Feign client as a whole could not be.

| | `findById` (GET) | `purchase` (POST) |
|---|---|---|
| Retry | **yes**, 3 attempts with backoff | **never** |
| Circuit breaker | yes | yes |

**Why the write is never retried.** Repeating a GET is free. Repeating a stock decrement is *selling the
book twice* — and the dangerous case is the one that looks like failure: book-service commits the
decrement and the response is lost coming back. The caller sees a timeout and cannot distinguish it
from "nothing happened". Retry and a second copy leaves the shelf; do not, and an order fails with
stock already gone. Neither is correct; losing stock is recoverable, overselling a customer is not.
The real fix is making the operation idempotent, which is 5d.

## The fallback does not invent data

Tutorials return a cached or dummy value from the fallback. For a price and a stock level that is
indefensible — charging a made-up price is worse than an error. The fallback here raises
`CatalogUnavailableException`, a clean 503, **immediately**.

**Failing fast is the fallback.** The value is not a substitute value; it is that the caller finds out
in microseconds instead of holding a thread for three seconds.

## Three bugs, all found by tests

**1. `ignore-exceptions` does not stop the fallback running.** It keeps business errors out of the
failure *rate* — but a fallback catches everything the guarded method throws. So "no such book" reached
the customer as **503 Service Unavailable**: the catalog had answered correctly and order-service
reported an outage. The fallback now rethrows business answers untouched.

That exclusion matters in the other direction too: a breaker that counts 404s as failures **opens under
entirely healthy traffic**, taking the catalog "down" because customers mistyped an id.

**2. Resilience4j nests Retry *outside* CircuitBreaker by default.** Two consequences: one retried call
records three separate failures against the breaker, and — worse — an open circuit's refusal travels out
to Retry, which retries *being told no* through the full backoff. "Fail fast" measured **619 ms**.

**3. Lower `Ordered` values run first, and therefore sit further out.** Fixing (2) by setting
`circuit-breaker-aspect-order: 2, retry-aspect-order: 1` changed nothing, because that put Retry outside
again. The build looked configured and behaved identically; only the failing test noticed. Correct is
`circuit-breaker-aspect-order: 1, retry-aspect-order: 2`.

## Circuit state is observable

`/actuator/circuitbreakers` and `/actuator/circuitbreakerevents` are exposed and, on this profile, open.
A breaker whose state nobody can see converts an outage into a mystery — "it started working again on
its own" is not an incident report. In a real deployment these are reachable only from inside the
cluster; the gateway never routes `/actuator/**` from outside.

---

# Step 5d — idempotency and a saga that survives a crash

Two problems have been outstanding since 5b, and they turn out to be one problem.

**5b's hole.** `place` reserved stock in book-service and *then* wrote the order. If the process died in
between, the stock was gone, no order existed, and nothing anywhere knew. No log, no row, no way to
notice.

**5c's dilemma.** The stock write could not be retried, because a lost response is indistinguishable
from a failure and a repeat would sell the book twice. 5c chose to under-sell and documented the choice
as a symptom.

Both come from the same root: **the operation could not tell two attempts apart.**

## Idempotent stock reservation

The caller chooses a `reservationId` *before* calling. book-service records it **in the same transaction
as the decrement**, and on seeing it again reports the current state without acting.

Measured against the running services:

```
same reservationId, three calls          without a reservationId, three calls
  call 1 -> stock 90                       call 1 -> stock 80
  call 2 -> stock 90                       call 2 -> stock 70
  call 3 -> stock 90                       call 3 -> stock 60
```

The left column is the fix, the right column is the bug it prevents. Releasing is idempotent too —
a second release does not credit the stock twice:

```
release -> stock 70
release -> stock 70
```

Writing the reservation in the same transaction as the decrement is not tidiness. Two transactions could
commit the decrement and lose the record, and the next retry would decrement again — exactly the bug
this exists to prevent. Two concurrent requests with the same id are settled by the primary key: one
loses, and its whole transaction including the decrement rolls back.

**With that in place, 5c's dilemma disappears.** `purchase` is retried again, and there is a test
asserting that every attempt carries the *same* id — a fresh id per attempt would make three
reservations and pass any test that only counted requests.

## The order is written before anything irreversible happens

```
1. read and validate         free, reversible, rejects bad orders before anything changes
2. write PENDING, COMMIT     ← the step 5b did not have
3. reserve stock             under ids already on disk; now safe to retry
4. mark AWAITING_PAYMENT     the saga is complete
```

`place` is deliberately **not** `@Transactional`. A transaction spanning it would be exactly the
illusion this step dispels — it cannot roll back anything book-service committed, and it would hold a
database connection open across two network calls.

Each step commits separately, through `OrderTransactions`. That is its own bean rather than a private
method for a reason first met in Step 1: **Spring AOP is proxy-based, so a self-invocation bypasses
`@Transactional` entirely**. Steps that silently did not commit would defeat the entire design, and
nothing would look wrong.

## The recovery job

Everything above handles failures the process is *present* for. None of it survives being killed between
two steps — which is exactly when inconsistency is created and nobody is left to notice.

An order stuck in `PENDING` is that footprint: healthy orders are PENDING for milliseconds. The job
finds them, releases the reservation ids committed with them in step 2, and marks them FAILED.

It ran for real on this platform, on eight orders left stranded by the 5b and 5c experiments:

```
Saga recovery: 8 order(s) stuck in PENDING since before 2026-08-02T04:41:05Z. Unwinding.
Saga recovery: order 1 unwound and marked FAILED
...
```

Two ordering decisions inside it:

- **Release before marking FAILED.** If the job dies midway the order is still PENDING and the next run
  retries; release is idempotent, so that is harmless. The other order risks an order that looks
  resolved while its stock is still held — the failure that leaves no trace.
- **Unwind rather than roll forward.** Completing a stranded order would require knowing whether each
  reservation took effect. Unwinding requires no such knowledge: releasing a reservation that was never
  made is a no-op. In recovery code, prefer the direction that needs less information.

## Still outstanding after 5d

- **Compensation is at-least-once, not exactly-once in the face of everything.** If book-service is down
  for longer than the recovery job's patience, stock stays held until it comes back. The job keeps
  retrying, which is the right behaviour, but "eventually" is doing real work in that sentence.
- **The recovery job runs on every instance.** With more than one replica they would race — harmless
  here because releasing twice is a no-op, but a shared lock or a single scheduled leader is the real
  answer, and Step 10 is where that becomes unavoidable.

---

# Step 5e — payment-service, and a saga that rolls forward

The fourth service, and the one whose mistakes involve somebody's money. That changes the answer to the
question 5d spent its time on.

## Same pattern, opposite direction

| | placing an order (5d) | paying for one (5e) |
|---|---|---|
| Step that can fail after a commit elsewhere | reserving stock | telling order-service |
| Cost of reversing it | a release call; invisible | a refund; slow, visible, chargeable |
| **So the saga** | **unwinds** | **rolls forward** |

`OrderRecoveryJob` cancels stranded orders. `PaymentRecoveryJob` *completes* stranded payments, and
never gives up:

```java
// No backoff limit and no giving up. There is no acceptable resting state for "customer charged,
// order unaware" — marking it resolved after N attempts would mean quietly deciding to keep the money.
```

**Which direction a saga points is a business decision about the cost of reversing each step, not a
property of the pattern.** The code shape is identical.

## Idempotency by natural key

Stock reservation needed a caller-supplied id, because "have I reserved this before?" has no natural
answer. Payment does: **one payment per order**, true regardless of who asks or how often. So the key is
`order_id UNIQUE`, and a caller cannot forget to send it the way it could forget a synthetic key.

Every duplicate route ends at that constraint — a lost response, a double-submitted form, a proxy
replay, two concurrent requests. Measured: paying three times produced one row and returned the same
payment each time.

## The failure that must not look like a failure

If the charge succeeds and telling order-service fails, `pay` still returns **success**. Returning an
error would tell a customer their payment did not work when it did, and invite them to pay again. The
payment is flagged un-notified and the recovery job finishes it.

Demonstrated by forcing exactly that state and watching it heal:

```
Payment recovery: 1 successful payment(s) order-service has not been told about
Payment recovery: order 12 marked paid from payment 3
```

## Two bugs worth the whole step

**1. A background job has no token to forward.**

`FeignAuthPropagation` reads the caller's token off the current request. `PaymentRecoveryJob` runs on a
timer, minutes after the customer left — no request, no token, so it called order-service anonymously and
got **401 on every attempt**. A recovery mechanism that could never recover anything, failing quietly in
a log nobody reads.

**Identity propagation covers synchronous work only.** Anything asynchronous — a scheduled job, a queue
consumer (Step 7), a retry after the caller has gone — needs an identity of its own.

`ServiceTokenProvider` mints one, and this is uncomfortable: Step 5a deleted `generate()` from
book-service arguing that two services able to mint credentials is two places to audit. The argument
still holds; the situation differs. book-service only ever acts for a caller who is present.
payment-service must act autonomously to finish a saga, and a service that acts on its own needs an
identity of its own.

What limits it: minted per call, two-minute lifetime, never stored, used only when no request is in
flight, and identifiable as `service:payment-service` in any log. What does not: the role is `ADMIN`,
which is more than the job needs. A distinct `SERVICE` role scoped to one route, or mTLS on an
internal-only endpoint, is the real answer — both Step 8 territory, once a gateway separates inside from
outside.

**2. The idempotent fast path skipped an authorization check.**

`pay` returns early when a payment already exists. That path did not check ownership, on the reasoning
that ownership is checked after reading the order — which it is, on the path that reads the order. So
once an order had been paid for, **any authenticated user** asking to pay for it got 201 and the payment
details back: amount, timestamp, and confirmation the order existed.

Every test of the slow path still passed. The shape generalises: **a fast path added for performance or
idempotency is a second code path, and it needs every check the first one has.**

## Next — Step 6

Four services now share a signing key as four copies of one literal, and each carries its own database
URL, timeouts and resilience thresholds. Changing any of them means editing four files and restarting
four services. Spring Cloud Config Server.
