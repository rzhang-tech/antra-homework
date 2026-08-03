# bookstore-platform — the services (Steps 5–11)

The monolith, split. It is preserved in Git at the `step-4-monolith` tag:

```bash
git checkout step-4-monolith
```

## Status

| Service | Port | Database | State |
|---------|------|----------|-------|
| `config-server` | 8888 | none | ☑ 6a |
| `api-gateway` | 8080 (9090 admin) | none | ☑ 8a |
| `user-service` | 8081 | `userdb` on 5433 | ☑ 5a |
| `book-service` | 8082 | `bookdb` on 5434 | ☑ 5a |
| `order-service` | 8083 | `orderdb` on 5435 | ☑ 5b |
| `payment-service` | 8084 | `paymentdb` on 5436 | ☑ 5e |
| `notification-service` | 8085 | none | ☑ 7a |
| `analytics-service` | 8086 | none | ☑ 7b |
| `cover-processor` | — | `CoverMetadata` (DynamoDB) | ☑ 9c — an AWS Lambda, not a service |

## Run it

Since 10b, all of it — eight services, four PostgreSQL instances and a Kafka broker — is one command
from `02-bookstore-capstone/`:

```bash
docker compose up -d
```

**34 seconds to thirteen healthy containers**, in dependency order, with no terminal count involved.
The only published port is **8080**; everything else is reachable on the compose network and nowhere
else, which is the promise Step 8 made and 10b keeps.

```bash
docker compose ps
```

```bash
docker compose down
```

Images are built on first use. To build them without starting anything, or to tag a release:

```bash
./scripts/build-images.sh
```

---

Everything below is the original workflow — eight terminals, in this order — and it still works,
unchanged, because 10b added overridable defaults rather than replacing addresses. Use it when you want
a debugger attached to one service.

**The config server starts first, and the other four will not start without it.** That is deliberate —
see Step 6a — and it needs the encryption key, or the dev signing key in the repo cannot be decrypted:

```bash
cd config-server && ENCRYPT_KEY=dev-only-config-server-master-key-do-not-reuse ../mvnw spring-boot:run
```

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

The two event consumers, which have no API and nothing calls:

```bash
cd notification-service && ../mvnw spring-boot:run
```

```bash
cd analytics-service && ../mvnw spring-boot:run
```

And the front door, which from Step 8 is the only address a client needs:

```bash
cd api-gateway && ../mvnw spring-boot:run
```

Step 9 needs AWS. Once, with credentials configured (`aws configure`):

```bash
cd ../scripts/aws && ./dynamodb-browsing-history.sh && ./s3-covers-bucket.sh
```

```bash
./cover-pipeline-infra.sh && ./deploy-cover-processor.sh && ./s3-lifecycle-policy.sh
```

Every script is idempotent. When you are finished, `./teardown.sh --yes` removes all of it — a capstone
that leaves resources running sends somebody a bill.

Then work through [test-platform.http](test-platform.http), which exercises the boundary itself: a token
minted on 8081, accepted on 8082.

Everything at once:

```bash
./mvnw test
```

139 tests across the nine modules. Testcontainers supplies the databases, and no test talks to the
config server, so nothing needs to be running.

Run `./mvnw clean` at least once after pulling Step 6: `target/classes` keeps a copy of every resource
that was ever compiled, including the per-service `application-dev.yml` files this step deleted. A stale
copy there is invisible, sits below the config server in precedence — and quietly becomes the fallback
the moment the config server cannot supply a value. Found the hard way; see Step 6d.

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

---

# Step 6 — one place to change configuration, and the honest limits of that

Four services shared a signing key as four copies of one literal, and each carried its own database URL,
timeouts and resilience thresholds. Changing any of them meant editing four files and restarting four
services — with a window in the middle where user-service signed with a key the others did not have.

## What moved, and the one rule that decided it

A service cannot fetch its configuration until it knows its own name and the address of the server
holding it. **That is the bootstrap paradox, and everything else follows from it.** Those two facts, and
nothing else, still ship inside each jar; `application-dev.yml` and `application-prod.yml` are gone from
all four services.

| Served by the config server | Stays in the jar |
|---|---|
| datasource URL, credentials | `spring.application.name` |
| ports | the address of the config server |
| the JWT signing key, issuer, expiry | Flyway migration **scripts** (`db/migration/*.sql`) |
| Feign timeouts, resilience thresholds | |
| log levels, actuator exposure | |

Migration scripts stay with the service that owns the schema. They are versioned artefacts that must
match the compiled entity classes; shipping `V3__add_column.sql` separately from the code that needs the
column is how a deployment half-applies itself.

## Property precedence is not what it looks like

```
GET /user-service/dev

  user-service-dev.yml     wins
  application-dev.yml      <- outranks the service's own file below
  user-service.yml
  application.yml          platform-wide default
```

**Profile beats specificity.** A key set in the shared `application-dev.yml` overrides the same key in
`user-service.yml`, and the service file simply appears to be ignored, with no warning anywhere. This
was measured against the running server rather than assumed, and `ConfigServerContractTest` pins it —
the test exists precisely because the intuitive order is the wrong one.

## Refuse to start rather than start wrong

`spring.config.import` is **not** marked `optional:`, and `fail-fast: true`. A service that falls back
to bundled defaults when the config server is unreachable comes up holding last release's signing key
and reports itself healthy. A process that will not start is a visible, obviously-attributable failure;
a process running on stale configuration is an invisible one.

Fail-fast is not fail-instantly — a cold start races the config server, and Step 10 makes that routine.
Measured with the server unreachable:

```
max-attempts 1     4.5 s
max-attempts 6    31.2 s      <- 26.7 s of it backing off
```

**Do not trust the startup log's timestamps here.** Config data is loaded before the logging system
exists, so those messages go through a `DeferredLog` and replay in a burst — six attempts appear two
milliseconds apart, which reads exactly like a backoff that is not working. The wall clock is the only
honest measurement.

## Tests never talk to the config server

Two separate mechanisms, both needed, for a reason worth knowing:

- `on-profile: "!test"` on the import stops the **fetch**. Config-data imports resolve in an early
  phase, before `application-test.yml` is visible, so disabling the client there could not have
  prevented it.
- `spring.cloud.config.enabled: false` in `application-test.yml` stops the **check** —
  spring-cloud-starter-config refuses to start a context that has the starter on the classpath and no
  `spring.config.import`. That check earns its keep in production, where what it catches is a service
  quietly running on bundled defaults.

The cost is that `application-test.yml` duplicates a few values the config repo also defines.
Deliberate: the alternative is a test suite that cannot run unless another process is up.

## A real bug, visible only once the files sat in one directory

Feign's connect and read timeouts were set in order-service's and payment-service's
`application-dev.yml` — and in **neither** `application-prod.yml`. Production was running on Feign's
defaults, effectively "wait forever": exactly the failure mode Step 5b added them to prevent, disabled
in the only environment where it matters. Two sub-steps of nobody noticing, because no single file ever
showed both profiles side by side.

That is a better argument for a config server than "less duplication".

## A test that had been passing for the wrong reason

`CatalogGatewayResilienceTest` was reading production's circuit-breaker thresholds by accident, because
`src/main/resources/application.yml` is on the test classpath. Once those moved to the config server the
test ran on Resilience4j's defaults — a 100-call window — and could no longer open a circuit at all.

Fixed by pinning the thresholds in `application-test.yml`, not by pointing tests at the config server. A
test asserting "the circuit opens on the sixth call" must own the numbers it asserts on, or it silently
changes meaning the moment operations tunes a threshold for entirely good reasons.

## Changing a value without restarting — and the three things that refused to

`POST /actuator/refresh` re-fetches and rebinds. Measured on the running platform, changing
`app.jwt.expiration-minutes` in the config repo and logging in again:

```
1. token lifetime, as configured        60 min
2. config changed, no refresh yet       60 min
3. POST /actuator/refresh               ["app.jwt.expiration-minutes"]
4. next login, same process             5 min
5. restored                             60 min
```

Log levels do the same with no code at all — DEBUG to WARN and back, through `/actuator/loggers`. That
is the endpoint's most common real use: turning on debug logging during an incident without restarting
the thing you are trying to observe.

**The lesson of the whole sub-step, in one line: `/actuator/refresh` always updates the Environment, and
updating the Environment changes nothing by itself.** Something has to be able to read the value again.
Three demonstrations, all measured:

| | result |
|---|---|
| `JwtUtil` captured expiry in its constructor | env says 5, tokens still 60 |
| `@RefreshScope` added, `JwtProperties` still a record | env says 5, tokens still 60 |
| `@RefreshScope`, `JwtProperties` a mutable class | env says 5, tokens 5 |

A record binds through its constructor, and the refresh machinery rebinds an *existing* instance — so
the rebuilt `JwtUtil` kept being handed the same stale properties. `JwtProperties` is now a mutable
class in user-service only: **immutability traded for the ability to change a value without a restart**,
deliberately, in the one service that needs it. Two beans in a chain, and refresh lands only when both
can be rebuilt.

`spring.cloud.openfeign.client.refresh-enabled` is the third. It refreshes Feign's `Request.Options` —
the timeouts — and **not** the client URL, whatever the name suggests. Verified by pointing
`app.book-service.url` at a dead port, refreshing, and watching order-service keep succeeding against
the old address.

### What still cannot be refreshed, and one that should not be

`server.port` (Tomcat has bound it) and the datasource URL (Hikari has built the pool) need a restart,
and that is fine — nobody re-ports a running service.

**Rotating the signing key is not a refresh problem, and it is worth being precise about why**, because
it is the value this entire step was built for. Refresh reaches one service at a time. The instant
user-service signs with a new key, every token already in a customer's browser and every service still
holding the old key disagrees with it. Real rotation needs a verifier that accepts both keys for longer
than a token lives, and a signer that switches only once every verifier has the new one. That is a key
*set*, which is a code change, not a configuration change. This platform does not have it.

## Actuator, split by what each endpoint can do

```
/actuator/health      open      a Kubernetes probe carries no token, and a health check
                                that answers 401 is a pod that never becomes ready
everything else       ADMIN     /actuator/refresh is a POST that rebinds beans in a running
                                process; /actuator/env discloses the whole configuration
```

Verified: no token gives 401, a USER token 403, an ADMIN token 200. user-service and book-service had no
actuator dependency at all until this step — order-service and payment-service only acquired it in 5c
for the circuit-breaker endpoints — and two of four services unable to answer a health check is not a
deployment story.

## Secrets: two mechanisms, because they solve different problems

**Dev uses `{cipher}`.** The signing key in `application-dev.yml` is ciphertext; the config server holds
the key in `ENCRYPT_KEY` and decrypts before serving, so no client needs the key or an extra dependency.

What that buys and does not buy:

- it **does** stop a config repository being a list of credentials in a Git history that everyone with
  read access can browse — including after a value is rotated, because Git keeps the old one forever;
- it **does not** stop anyone who can reach the config server from reading the plaintext, since that is
  precisely what it hands to clients. The key protects the repository, not the wire.

> **And in this repository the key is written down two files away from the ciphertext it protects**,
> which is precisely what `config-server/application.yml` says not to do. That is a deliberate,
> stated exception rather than an oversight: a capstone has to be runnable by whoever clones it, and a
> dev signing key nobody can decrypt makes the project un-startable. What is demonstrated here is the
> *mechanism*; what is relied on in production is `${JWT_SECRET}` (D18), which keeps the secret out of
> the repository entirely and therefore needs no such exception. Read this pairing as "how `{cipher}`
> works", not as "this repository's dev key is protected".

**Production uses `${JWT_SECRET}`.** Placeholders are *not* resolved by the config server — verified:

```
GET /user-service/prod
  app.jwt.secret        -> ${JWT_SECRET}
  spring.datasource.url -> ${DB_URL}
```

The client resolves them against its own environment, so the secret never sits in the repository, never
travels over the wire, and never reaches the server's logs. Strictly stronger than encryption, and it is
the mechanism that survives Step 10 unchanged — `${JWT_SECRET}` is exactly what a Kubernetes Secret
mounted as an environment variable supplies.

Starting the config server without `ENCRYPT_KEY` fails loudly in the right place, which was checked
rather than assumed:

```
config server   WARN  Cannot decrypt key: app.jwt.secret
                      serves it renamed to `invalid.app.jwt.secret`
client          APPLICATION FAILED TO START
                      Property: app.jwt.secret   Reason: app.jwt.secret must be set
```

`/encrypt` and `/decrypt` are unauthenticated, because this server has no security at all. Anything that
can reach `/decrypt` can turn ciphertext back into a credential. It belongs on an internal network
behind Step 8's gateway, and `/decrypt` is worth disabling outright once nothing needs it.

## The bug that made all of the above nearly untrue

That loud failure did not happen the first time it was tried. user-service started perfectly and minted
working tokens against a config server that could not decrypt anything.

`target/classes/application-dev.yml` — a compiled copy of a file deleted back in 6a. `spring-boot:run`
puts `target/classes` on the classpath, Maven never removes resources that are no longer in `src`, and
nothing anywhere reports it. Config server property sources outrank it, so every earlier measurement in
this step remains valid; but the instant the config server could not supply the key, the ghost file
supplied it instead, and fail-fast became fail-never.

**A deleted configuration file is not gone until `mvn clean` runs.** The general shape is worth keeping:
a stale artefact that is outranked in the normal case is invisible until exactly the failure case it
would have masked.

## How this maps to Kubernetes (Step 10)

The Config Server is the classic Spring Cloud answer and is expected in interviews. It is not what most
Kubernetes deployments use, and the mapping is close to one-to-one:

| here | Kubernetes |
|---|---|
| `config-repo/*.yml` | a `ConfigMap` per service, plus one shared |
| `{cipher}` values | a `Secret`, or an external store via the Secrets Store CSI driver |
| `${JWT_SECRET}` placeholders | `env.valueFrom.secretKeyRef` — **unchanged**, which is the point |
| `spring.config.import: configserver:` | nothing: the kubelet injects the values as env vars or files |
| `POST /actuator/refresh` | a rolling restart, or a sidecar that watches the ConfigMap |

The honest comparison: ConfigMaps need no extra service to run, no extra failure mode at startup, and no
network hop — the config server is one more thing that must be up before anything else can start, which
is precisely what `fail-fast` had to be designed around. What the config server has and ConfigMaps do
not is **Git as the source of truth**: an author, a timestamp and a diff for every production
configuration change, and revert as an ordinary operation. Enough teams run both that knowing which
problem each one solves matters more than picking a winner.

## What got worse

- **A fifth process, and everything depends on it.** The config server is now a startup-order dependency
  for the whole platform. Retry and fail-fast make its absence loud rather than harmless, which is the
  right trade, but it is a new single point of failure and Step 10 has to make it highly available.
- **"Where does this value come from?" now has four possible answers per service**, ordered by a rule
  that is not the obvious one. `/actuator/env` is the only reliable way to answer it, which is why it is
  exposed.
- **Configuration and code can now drift apart.** A resilience threshold naming
  `com.example.order.exception.ResourceNotFoundException` lives in a YAML file that no compiler checks;
  rename the class and the config server will keep serving the old name perfectly happily.

---

# Step 7 — things that happen without anyone waiting

Before this step, everything a placed order caused happened inside the customer's request. Adding "send
a confirmation" would have meant another synchronous call on the critical path: another thing that can
be slow, another thing that can fail, and another reason an order does not go through. Adding
"count it for analytics" would have meant a second one.

Two new services now react to orders, and order-service knows about neither.

## What it bought, measured

Placing an order:

```
HTTP 201 in 0.59s        published at 09:47:57.281
                         consumed  at 09:47:57.340, other process, other thread
```

With notification-service **stopped**:

```
order 1 -> HTTP 201 in 0.073s
order 2 -> HTTP 201 in 0.067s
order 3 -> HTTP 201 in 0.060s

then, on restart, with nobody asking:
09:49:04  CONFIRMATION ... order 20   (placed 09:48:23)
09:49:04  CONFIRMATION ... order 21
09:49:04  CONFIRMATION ... order 22
```

A consumer being down is not an outage; it is a queue. That single property is what the whole step is
for, and it is why `placedAt` travels **in the payload** rather than being read off the record's
timestamp — a consumer forty seconds or four hours behind still has to say when the order was placed.

## Two consumer groups, and the one-character bug that hides in them

analytics-service reads the same topic as notification-service under a different group id. Starting it
for the first time:

```
analytics-service starts   -> 4 TALLY lines, the whole history replayed
notification-service       -> 0 new confirmations
```

Offsets belong to the **group**, not to the topic. From the broker itself:

```
$ kafka-consumer-groups.sh --describe --all-groups

analytics-service     bookstore.order.placed  partition 0  offset 3/3  lag 0
notification-service  bookstore.order.placed  partition 0  offset 3/3  lag 0
```

**Give the two services the same group name and nothing errors.** Kafka would split the partitions
between them, so roughly a third of orders would be confirmed but not counted and another third counted
but not confirmed. The symptom is "analytics seems to be missing some orders", and it is invisible with
one instance of each against a single-partition topic. That is why the group name is written down in
the config repo with a comment, rather than defaulted from the application name.

## Keying, and what it is actually for

Every event is keyed by order id. The key chooses the partition, and Kafka orders records **within a
partition and nowhere else**:

```
order 19 -> partition 1     order 21 -> partition 0
order 20 -> partition 1     order 22 -> partition 0
                            order 23 -> partition 0
```

Without the key, records round-robin, and "order placed" and "order cancelled" for the same order can be
processed by different threads in either order. That bug appears only under load, only sometimes, and
never on a one-partition development topic.

Partition count is close to permanent: raising it re-hashes keys onto different partitions, so a key's
history splits across two of them and per-key ordering breaks for everything already written.

## The cross-service serialization trap

Spring's `JsonSerializer` writes the producer's fully-qualified class name into a `__TypeId__` header,
and a `JsonDeserializer` downstream obeys it. So order-service would be instructing notification-service
to instantiate `com.example.order.event.OrderPlaced` — a class that does not exist there and must not
(D12). The fix everyone reaches for is a shared jar of event classes, which recreates exactly the
coupling the split removed.

Type headers are off at the producer instead, and the type is chosen by the `@KafkaListener` method's
own signature via a message converter. **A producer publishes facts, not Java types.** That decision
paid for itself in 7c: notification-service consumes a second topic with a different event type, and it
took a new method and nothing else.

Each service keeps its own copy of every contract, which means each copy is a copy of *some past
version*. That is the actual situation rather than a flaw, and it dictates the rule: unknown fields are
ignored, missing fields tolerated. There is a test for it — a producer adding a field must not take the
notification pipeline down.

## At-least-once is a statement about your code

A consumer processes a record and then commits its offset. Die in between, and the next owner of that
partition delivers it again. Committing first would trade duplicates for silent loss, which is why
`enable-auto-commit: false` is set and why guards exist. A rebalance, a slow poll, a restarted pod and
a producer retry all produce the same effect.

**The broker cannot fix this for you.** Producer idempotence removes duplicates one producer session
creates; Kafka transactions give exactly-once between topics. Neither covers "this consumer added a
number to a total twice", because that side effect lives outside Kafka.

Republishing the same `OrderPlaced` twice into the live topic:

```
analytics-service     Order 25 has already been counted; ignoring redelivery      (x2)
                      revenue unchanged at 30.13
notification-service  Order 25 has already been confirmed; ignoring redelivery    (x2)
                      no second confirmation
```

Four decisions inside that:

- **The key is the order id, not a message id.** Same reasoning as payment-service's `order_id UNIQUE`
  in 5e: a natural key cannot be forgotten and cannot be regenerated. A per-message UUID deduplicates
  only identical retransmissions — republish after a producer restart with a fresh UUID and it counts
  twice again.
- **The guard lives in the work, not in the listener.** "Have I already counted this order?" would need
  answering just the same if the events arrived over HTTP or were replayed from a file.
- **`ReceiptSender` has its own guard**, not one shared with `ConfirmationSender`. Sharing a "have I
  seen order 17?" set would mean confirming an order suppressed its receipt. Idempotency keys identify a
  unit of work, not an entity.
- **One call that both asks and records.** `hasSeen` then `markSeen` is a check-then-act race, and these
  services consume three partitions concurrently.

### The asymmetry worth noticing

analytics-service's guard protects an in-memory tally, so guard and state are lost together and cannot
disagree. notification-service's protects an email that has genuinely left the building — restart it
while a redelivery is outstanding and the customer gets a second confirmation, because the guard forgot
and the inbox did not.

**An idempotency guard must be at least as durable as the effect it guards.** Both stores here are also
bounded, so a redelivery older than the last 10,000 ids would slip through. Acceptable when redelivery
happens within seconds; not acceptable in a system where it might not, where the guard belongs in a
table with a retention policy or in Redis with a TTL longer than the worst redelivery window.

## Which side effects became events, and which did not

payment-service publishes `PaymentCompleted` **and still calls order-service synchronously** to mark the
order paid. 5e's recovery job still never gives up.

That is not an oversight. **An event tells people something happened; a call makes something happen.**
order-service needs the money to have arrived before it hands over books, and "eventually, once a
consumer catches up" is not a guarantee to put between a customer and their order. The event is for
everyone else — receipts today, fraud scoring and revenue reporting later — none of whom payment-service
should have to know about.

Only successful payments are published. A declined payment is information a fraud service would want and
a receipt service must never act on, and a topic is read by consumers you cannot enumerate. A separate
`payment.declined` topic is the shape that scales, if anyone ever needs one.

## The poison message, and why a dead letter topic is not optional

Ordering is per partition, and the container honours it: a record that keeps throwing is retried and
**every later record on that partition waits behind it**. One malformed message stops a third of a
service's work indefinitely, and the only symptom is a consumer that has gone quiet.

A poison message published to the live topic, followed immediately by a good one **on the same
partition**:

```
13:25:13.188  WARN  Attempt 1 failed for bookstore.order.placed-0 offset 6: Listener failed
13:25:13.760  INFO  CONFIRMATION to user 1: order 1000 accepted, total 11.00
```

**572 milliseconds**, and the partition kept moving. The bad record went to the dead letter topic on the
first attempt rather than after three, because a conversion failure is deterministic — the same bytes
fail the same way forever, and retrying is pure delay. Transient failures (a downstream timeout, a
database blip) do get the retry budget: three attempts over about seven seconds.

The retry budget is deliberately small. The DLT is the last line of defence, not the retries, and a long
budget converts "one bad message" into "this partition is minutes behind" — the same outage arriving
more slowly.

### The DLT is named per consumer group

Spring's default is `<topic>.DLT`. Two services read `bookstore.order.placed` here, so the default would
pour both services' failures into one topic — and then "how many notifications are stuck?" cannot be
answered without inspecting every record, and a replay tool would have to re-deliver analytics failures
to notification-service to find its own.

```
bookstore.order.placed.notification-service.DLT
bookstore.order.placed.analytics-service.DLT
bookstore.payment.completed.notification-service.DLT
```

### A dead letter topic without a monitor is worse than none

The DLT's purpose is to get a failing message out of the way so the partition keeps moving. That is also
its danger: **the symptom of failure is removed along with the failure.** Before the DLT, a poison
message made a consumer visibly stop. After it, the consumer looks perfectly healthy while orders quietly
go unconfirmed, and nobody finds out until a customer asks.

`DeadLetterMonitor` counts records in every DLT on the platform — including notification-service's,
because a monitor that only watched its own would leave the other service's failures unobserved:

```
13:25:14  WARN  DEAD LETTER: bookstore.order.placed.notification-service.DLT holds 1 message(s)
                nothing has dealt with. Read them, fix the cause, then replay or discard deliberately.

$ curl localhost:8086/actuator/metrics/bookstore.dlq.depth
{"name":"bookstore.dlq.depth","measurements":[{"statistic":"VALUE","value":1.0}]}
```

Depth (end offset minus start offset), not consumer lag: nothing consumes these topics, a human does,
and the alert-worthy threshold is **one**. It says nothing at all when every DLT is empty — a monitor
that logs "0 messages" every thirty seconds trains everyone to skip its output.

In a real deployment this is a Prometheus alert with an owner, not a scheduled method inside a service —
a monitor living inside the service that is failing can fail with it. The value here is knowing what to
alert on.

## What got worse

- **A dual-write hole, and it is not fixed.** The order is committed and the send can still fail,
  leaving an order nobody was told about — no error, no retry, no trace. A database write and a Kafka
  send cannot be one atomic act. The answer is a transactional outbox: write the event into the order's
  own database in the same transaction as the order, and let a poller publish it. Stated plainly in
  `OrderEventPublisher` rather than papered over, and the largest thing this step leaves undone.
- **"What happens when an order is placed?" is no longer answerable by reading order-service.** The
  synchronous version was traceable in a debugger. This one requires knowing which topics exist and who
  subscribes to them, and the answer changes when somebody deploys a new consumer.
- **Two more processes**, neither of which anything monitors except by reading logs, and both of which
  keep their state in memory.
- **The event contracts have no schema registry.** Each service holds a hand-written copy, and nothing
  fails at build time when a producer renames a field — only a consumer, at runtime, quietly binding
  null. Avro or JSON Schema with a registry is the industrial answer; the test that pins the wire format
  is this project's smaller one.

---

# Step 8 — one front door, and what must not be put behind it

Seven services had, between them, six public addresses, four independent answers to "what is allowed
without a token", and no shared place to configure CORS. A browser had to know all of it.

There is now one address, `http://localhost:8080`, and the whole platform runs through it — login,
browse, order, pay — with nothing calling 8081-8084 from outside.

## What the gateway must never become

A gateway is the easiest place on a platform to put things, and therefore the easiest to ruin. Three
rules, written into `ApiGatewayApplication` so they survive the next person with a good idea:

- **No business logic.** A rule here applies to every service by accident, cannot be tested with the
  service it belongs to, and makes one deployable a dependency of all seven.
- **No aggregation.** The tempting "one call that returns an order with its books and its payment"
  makes the gateway a client of three services with three failure modes and three timeouts. That is a
  backend-for-frontend, and it belongs in its own deployable if it is ever wanted.
- **No stored state.** Everything it knows comes from the request or the config server, which is what
  lets it scale to N instances that agree about nothing.

What it legitimately owns — routing, edge authentication, CORS, later rate limiting and tracing — are
all properties of *the edge* rather than of any service.

## Routing

Routes live in the config repo, because a route is configuration:

```
/api/auth/**, /api/users/**       -> user-service
/api/books/**, /api/authors/**    -> book-service
/api/orders/**                    -> order-service
/api/payments/**                  -> payment-service
```

Paths are forwarded **unchanged** — no `StripPrefix`, no `RewritePath`. A client's URL and a service's
URL being the same string means a stack trace, a log line and a curl command all refer to the same
thing, and it means putting a service behind the gateway changed nothing for callers.

**Order matters and nothing warns.** Predicates are evaluated top to bottom and the first match wins, so
a broad path above a narrow one silently swallows it. `ConfigServerContractTest` asserts the indices as
well as the contents for exactly that reason.

**`/actuator/**` is not routed, and that is the point.** Since Step 5c several comments justified
leaving `/actuator/circuitbreakers` open on the grounds that "the gateway never routes `/actuator/**`
from outside". This is where the promise is kept: every route starts `/api`, so there is no path from
8080 to any service's actuator. It needed no rule — only the absence of a catch-all.

notification-service and analytics-service appear nowhere in the route table. They have no API; a route
to them would be a route to nothing.

## The gateway authenticates; the services authorize

The edge checks that a token exists, is genuine and has not expired, then gets out of the way. It does
not know that only an ADMIN may delete a book, or that a customer may read only their own orders, and it
must not learn — **a rule stated in two places drifts, and the copy on the edge is the one nobody
remembers to update.**

What the coarse half buys, measured:

```
GET /api/orders, no token       401
GET /api/orders, bad token      401

order-service log lines produced by those two requests:   0
order-service log lines produced by one accepted request: 28
```

A request with no token costs a service nothing — no connection, no thread, no database session. Under a
credential-stuffing attempt that is the difference between an inconvenience and an outage.

### Why the services still verify every token

The tempting next step is to strip the token here, forward `X-Auth-User-Id`, and let the services trust
it. Don't. Anything able to reach a service directly — another pod, a port-forward, a misconfigured
NetworkPolicy — could then claim to be anybody, and the platform's whole authorization model would rest
on network topology that nothing enforces. **A network boundary is not a security boundary until
something makes it one** (mTLS, a service mesh, an authenticated internal identity), and this platform
has none of those yet.

So the token is forwarded untouched, and the identity headers are for logs and traces only.
Demonstrated rather than argued:

```
forged X-Auth-Role: ADMIN through the gateway      401  (and the gateway logs the attempt)
forged X-Auth-Role: ADMIN straight at 8083         401
forged X-Auth-Role: ADMIN + DELETE /api/books/1    401
```

### Stripping inbound `X-Auth-*` is the most important line in the filter

The moment any downstream code reads `X-Auth-Role` — and *"the gateway always sets it"* is exactly the
reasoning that gets there — a curl command becomes a complete authentication bypass unless the header is
cleared on the way in. **Headers a proxy sets must be headers a proxy also clears.** It is
unconditional: on public routes, and on requests about to be refused, because the value of the
guarantee comes entirely from having no exceptions to reason about.

A genuine customer sending `X-Auth-Role: ADMIN` alongside a valid token is allowed through — and arrives
describing the person the *token* says they are.

### The one duplication Step 8 accepts

The gateway's public-route list mirrors `permitAll` rules the services already have. If book-service
later closes `GET /api/books` and nobody edits the list, the edge lets it through and the service
refuses it. **That direction is safe** — the service is the authority and it says no. An edge that
*granted* what a service denied would be a vulnerability; this is only a wasted hop.

## CORS, once

Before the gateway there was no sensible place for it: six services would have needed six identical
blocks, and a browser calling two of them would have been at the mercy of whichever was edited last.

```
$ curl -i -X OPTIONS localhost:8080/api/orders \
    -H 'Origin: http://localhost:3000' -H 'Access-Control-Request-Method: POST'

HTTP/1.1 200 OK
Access-Control-Allow-Origin: http://localhost:3000
Access-Control-Allow-Headers: authorization, content-type
Access-Control-Max-Age: 3600
```

**200, on a protected route, with no token** — and that is a filter *ordering* property, not a
configuration one. A browser sends `OPTIONS` with no `Authorization` header at all; answering the
preflight with 401 makes the real request never happen, and the developer sees a CORS error in the
console with nothing anywhere mentioning a token. `EdgeAuthenticationFilter` runs at
`HIGHEST_PRECEDENCE + 100` to leave room for it, and `CorsTest` pins the behaviour because YAML cannot.

`Authorization` must be listed explicitly: it is not a CORS-safelisted header, so omitting it fails
every authenticated browser request. Origins are named rather than `*`, and `allow-credentials` is
false because this platform authenticates with a bearer header rather than a cookie.

### CORS is not access control

The most common misreading in web development, so it is pinned as an assertion:

```
valid token, no Origin header at all      200      <- curl is entirely unaffected
valid token, disallowed Origin            403
no token,   allowed Origin                401
```

CORS is a **browser** mechanism, and the check keys on the `Origin` header. Every server-side client,
script and attacker simply omits it. Removing an origin from the list stops a *page* on that origin
from reading responses in a browser; it stops nothing else. The authorization that matters is the token
check at the edge and the rules inside each service.

(Worth knowing: Spring rejects the *actual* cross-origin request too, not just the preflight. It is
easy to assume CORS is preflight-only.)

## A hole this step opened and closed

`curl localhost:8080/actuator/env` returned **200, with the platform's configuration in it**, on the one
component facing the public internet.

Every service got ADMIN-only actuator in Step 6c, enforced by its Spring Security filter chain. The
gateway has no filter chain: `EdgeAuthenticationFilter` is a `GlobalFilter`, and a `GlobalFilter` runs
only for requests the route table matched. `/actuator` is served by a different handler mapping, so the
filter never saw it.

Two possible fixes — add a security starter and rebuild the same ADMIN rule a fourth time, or stop
serving actuator on the public port at all. The second is stronger: no rule to get wrong, no filter
ordering to reason about, and in Step 10 only 8080 is named in the Service, so the management port is
unreachable from outside the pod *by construction* rather than by policy.

```
                          8080 (public)    9090 (management)
/actuator/env                  404               200
/actuator/health               404               200
/actuator/gateway/routes       404               200
/api/books                     200                 -
```

**The general shape is worth keeping.** A guard attached to one mechanism (the routing filter chain)
does not protect what arrives through another (the actuator handler mapping). "Everything goes through
the filter" was true of the requests anyone was thinking about.

## Tested in two halves, on purpose

The real route table and CORS block live in the config repo, and tests do not read the config server —
the rule in [config-repo/README.md](config-repo/README.md), arriving for the fourth time:

| | asserts |
|---|---|
| `RoutingTest`, `EdgeAuthenticationTest`, `CorsTest` | what Gateway **does** with a route table: forwards the path, keeps `Authorization`, 404s an unmatched path, refuses without a token, strips forged identity, answers a preflight — against WireMock, using a stand-in table |
| `ConfigServerContractTest` | that the **real** table names the right services, paths and ports, in the right order |

Without the second, renaming a path in the config repo would break every client and pass every test.

`RoutingTest` had been sending `Bearer a.b.c` and getting 200. The moment the edge filter appeared, two
of its tests failed — the filter proving it was in the request path rather than merely configured.

## What got worse

- **A single point of failure with a queue behind it.** Every request now depends on one more process.
  Gateway being stateless makes it horizontally scalable, which is the answer, but Step 10 has to
  actually run more than one.
- **The public-route list is duplicated**, in the safe direction, and nothing checks that it still
  matches the services' own rules.
- **The gateway holds the signing key.** It only verifies, and it has no way to issue — no users table,
  no password encoder, no login route — but it is now a component on the public edge that possesses a
  credential. The real answer is asymmetric keys: user-service signs with a private key and everything
  else verifies with a public one, so a compromised verifier cannot mint anything. That is a change to
  every service, and it is the first thing on the Step 11 list.
- **Nothing rate-limits.** The edge is the only place that could, and it does not.

---

# Step 9 — two features that happen to live on AWS

Not a deployment step. Three new endpoints in book-service, one Lambda, two DynamoDB tables, a bucket
and a topic — feature work whose infrastructure is managed rather than run.

Everything below was created by the scripts in [scripts/aws](../scripts/aws) and measured against the
real account. `teardown.sh` removes all of it.

## Feature B first, deliberately

Browsing history (9a) was built before cover processing (9b–9c) because it is the same integration with
the fewest moving parts: one table, one SDK, no events, no IAM role, no Lambda. When the first AWS call
fails — and the first one always does — the question worth being able to answer is "is it my
credentials, my region, or my code", and a step with three of those four removed answers it quickly.

## Browsing history

Every book view by a logged-in customer is recorded; `GET /api/books/me/history` returns recent views
newest-first. Measured through the gateway:

```
GET /api/books/2   200 in 0.337s     first call - SDK and TLS warm-up
GET /api/books/3   200 in 0.027s
GET /api/books/4   200 in 0.024s
GET /api/books/2   200 in 0.024s     no token at all

GET /api/books/me/history  ->  2, 4, 3, 2     newest first
```

And read straight out of DynamoDB rather than trusted from the API:

```
bookId 2 | viewedAt 2026-08-02T23:04:41.890766400Z | expiresAt 1788303881 -> 2026-09-01
table Count: 4
```

**Four items, not five.** The anonymous read wrote nothing: there is no partition key for a person who
has not said who they are, and defaulting to one would file somebody's views under somebody else's.

### The keys

| | why |
|---|---|
| **`userId`** partition key | Many users, each a modest independent stream, so writes spread evenly and no partition is hot. It is also the only question ever asked of this table. `bookId` would make a best-seller a hot partition — DynamoDB throttles **per partition**, not per table — and a date would put every write of the day on one partition, the hottest key possible. |
| **`viewedAt`** sort key | ISO-8601 UTC, because DynamoDB orders sort keys **as strings** and ISO-8601's lexicographic order is its chronological order. A local timestamp, an offset like `+05:00`, or epoch millis as a string would each sort plausibly and wrongly. |
| `ScanIndexForward=false` | Reads the sort key backwards, so `limit` applies to the **newest** items. Ascending with a limit returns the ten oldest; ascending without one reads the user's entire history to show ten rows, and gets slower every day they use the site. |

### Three things that fail silently

- **TTL is epoch SECONDS in a NUMBER attribute.** DynamoDB ignores a TTL attribute of the wrong type
  without complaining, and milliseconds would set expiry to the year 33658 — the table grows forever
  and nothing reports a problem. The test asserts the **digit count**, because a range check passes for
  millis if the expectation is also millis.
- **TTL is not a query guarantee.** DynamoDB deletes expired items on its own schedule, typically within
  48 hours, and returns them from queries until it does. The `FilterExpression` on `expiresAt` is the
  actual correctness boundary, not belt-and-braces.
- **Spring Boot's default `@Async` executor is `SimpleAsyncTaskExecutor`**, which starts a new thread
  per task without limit. Under the traffic spike where recording a view matters, that is a thread per
  request until the JVM dies.

### The rejection policy is a business decision

The executor is bounded in **both** pool and queue — a bounded pool with an unbounded queue is
unbounded memory with extra steps — and **discards** on rejection:

| policy | what it costs |
|---|---|
| `CallerRunsPolicy` | the request thread does the DynamoDB write, so the catalogue read is now as slow as DynamoDB — precisely what doing this asynchronously avoids |
| `AbortPolicy` (default) | the same, plus an exception in a read path that has nothing to do with history |
| **`DiscardPolicy`** | one row missing from a list nobody audits |

Discard is right here and would be indefensible for an order, a payment or an audit log. What it must
not be is silent, hence the logged handler: a feature that quietly stops working under load is
indistinguishable from one that works.

## Covers on S3

`POST /api/books/{id}/cover` (ADMIN) uploads; `GET /api/books/{id}/cover` (PUBLIC) redirects.

```
POST no token / USER / ADMIN      401 / 403 / 204
GET  cover, no token              302 -> S3 -> 200, 78 bytes, image/png, byte-identical
GET  cover of a book without one  404
```

### The object key is `covers/{bookId}` and everything rests on it

Deterministic — no UUID, no timestamp, **no extension**. After two uploads:

```
list-objects-v2       --prefix covers/    ->  1 object
list-object-versions  --prefix covers/2   ->  2 versions
```

One object, two versions: replaced in place, previous recoverable because the bucket is versioned. A
random key would leave the old cover behind and make "the cover of book 42" need a lookup table — and,
more importantly, the Lambda derives its DynamoDB key from this same string, so a redelivered event and
a genuine re-upload must produce the **same** key or the pipeline gets a second row and a second email.
An idempotency key that has to be generated and carried is one somebody eventually forgets (D21).

No extension for a smaller version of the same reason: `covers/42.png` and `covers/42.jpg` are two
objects. The content type travels as S3 object metadata, which also makes the object self-describing —
`head-object` shows `Metadata {"book-id": "2"}`.

### Upload and download are deliberately asymmetric

**Upload streams through the service** because the bytes must be checked before they are accepted:
role, content type, size, and that the book exists. A presigned PUT — the scalable shape — hands the
client a URL that bypasses all four.

**Download redirects to a presigned GET**, because streaming an image through a Java service costs a
thread and the service's bandwidth for the whole transfer, which is exactly the workload S3 exists to
take off you. Presigning makes **no network call**: it is an HMAC over a canonical request, computed
locally, so the service does microseconds of work and S3 does the megabytes.

What that gives up, stated: a presigned URL works for anyone holding it until it expires. Fine for a
cover the requirement makes public; it would need considerably more thought for a private object, and
*"it is only a signed URL"* is how private objects end up in a chat log.

### Three status codes that were wrong

| | was | is | why |
|---|---|---|---|
| a `text/plain` file | 500 | **400** | `IllegalArgumentException` was unmapped. A 500 is the server claiming a bug it does not have, and sends whoever reads the log to debug the wrong thing (5b). |
| a 6MB file, limit 5MB | 500 | **413** | Not 400 either: the request was perfectly well formed, it was simply too big, and 413 tells a client something it can act on. |
| cover for book 9999 | — | **404** | Checked *before* the put, so no orphaned object and no email about a book that is not in the catalogue. |

## The Lambda

S3 `ObjectCreated` on `covers/` triggers a Java 21 function that reads the object, extracts its facts,
writes `CoverMetadata`, and publishes to SNS. book-service does not know it exists and does not wait for
it — the upload returned 204 before the function was invoked, which is Step 7's decoupling bought with
an event source instead of a broker.

```
bookId            4
objectKey         covers/4
sizeBytes         44371
contentType       image/png
width             120          <- read out of the image, not off the request
height            180
processedAt       2026-08-02T23:46:31.646273391Z
processedVersion  ARGfg9H9NLcjVL8x4Yj_YaP_7RXxbuyM
```

### Idempotency, measured

Replaying the **exact same event** — same object, same version:

```
returned: "processed=0 skipped=1"
processedAt before: 2026-08-02T23:46:31.646273391Z
processedAt after:  2026-08-02T23:46:31.646273391Z    unchanged
```

A **genuine re-upload** — different image, new version:

```
sizeBytes    44371 -> 78
dimensions   120x180 -> 10x10
rows in CoverMetadata: 1     updated, not duplicated
```

Two mechanisms, doing different jobs:

- **The primary key is `bookId`**, so one book has one row forever. A redelivery *updates* rather than
  inserting, and no cleanup job is ever needed.
- **The write is conditional** on `attribute_not_exists(bookId) OR processedVersion <> :version`, so a
  redelivery of the same version writes nothing and sends nothing. A new version passes, because an
  administrator who replaced a wrong cover wants to know the new one was processed.

Enforced by the database rather than by a read-then-write in the function: two concurrent invocations of
the same event would both pass a check-then-act, and only one can win a conditional write.

**The write happens before the publish**, deliberately. Reversed, a crash between them would send an
email and leave no record, so the retry would send a second one. The ordering that costs at most a
missing email beats the one that costs a duplicate — the same reasoning as 5d's "release before marking
FAILED".

### Lambda details that are not obvious

- **512 MB, not the 128 MB default, and it is not about memory.** Lambda allocates CPU *in proportion*
  to memory, so a 128 MB function gets roughly a tenth of a core — and a JVM cold start on a tenth of a
  core is measured in many seconds. Raising memory on a Java Lambda usually makes it **cheaper**,
  because billing is memory × duration and duration falls faster than memory rises.
- **SDK clients are `static`.** AWS reuses a warm container, so a static initialiser runs once per
  container rather than once per event. For a client that resolves credentials and warms TLS that is
  the difference between a 3-second cold start and a 40 ms warm one.
- **`UrlConnectionHttpClient`** instead of the SDK's default Netty: smaller jar, faster cold start, and
  this function makes three sequential calls and needs no connection pooling.
- **The execution role is written out by hand**, naming every resource. This is the one place in the
  project where least privilege is practised rather than discussed: `AmazonS3FullAccess` would have
  worked and would have given an image-processing function the ability to delete every bucket in the
  account.
- **Two permission directions.** The execution role says what the function may do; `add-permission`
  says who may *invoke* it. Forgetting the second produces a bucket notification AWS accepts and never
  fires, which looks exactly like a Lambda that is mysteriously not being triggered.
- **`URLDecoder` on the key.** S3 URL-encodes it in the event, so `covers/1 2` arrives as `covers/1+2`
  and the symptom is a `NoSuchKey` for an object that plainly exists.
- **The event's `versionId` is read, not "whatever is there now".** Two uploads in quick succession
  would otherwise both read the second image, and the metadata for the first version would describe the
  second.

## Dead letter queue, and the alarm

An asynchronous invocation that keeps failing is retried twice and then the **event** — not the error —
is sent to an SQS queue.

```
messages in the DLQ: 1
  ErrorMessage  The specified key does not exist. (Service: S3, Status Code: 404)
  errorType     software.amazon.awssdk.services.s3.model.NoSuchKeyException
  RequestID     60e54166-2c52-415c-b923-ca9ce0ae103d
  the event     key=covers/777777
```

The original event is preserved, so it can be fixed and replayed by hand. That is the point of a DLQ:
the poison message leaves the retry path without leaving the building.

### The first version of this test passed while testing something else

Worth keeping, because the failure mode is the interesting part. The first attempt used a hand-written
event missing fields the `S3Event` type requires, and the DLQ duly received a message:

```
ErrorMessage  An error occurred during JSON parsing
```

That is a **deserialization** failure. The Lambda runtime could not build the input object, so
`handleRequest` never ran — the S3 read, the conditional write and the publish were not executed at
all. The DLQ *plumbing* was proven; the thing the test existed to prove, that **a failure inside the
function's own code reaches the DLQ**, was not.

The difference is not pedantic. This function deliberately swallows one exception — `ImageIO` failing on
a format it cannot read — so a missing dimension does not stop the pipeline. Widen that `catch` by
accident and a genuine S3 or DynamoDB failure would be swallowed too, reaching neither the retries nor
the queue. **The first test could not have detected that, because it never reached the code.**

### And doing it properly found something else

The corrected test first failed with **403 AccessDenied on `s3:ListBucket`**, not the expected 404.

That is S3 behaving correctly: without `s3:ListBucket`, a caller must not be able to learn which keys
exist by comparing 403 against 404, so a missing object and a forbidden one are made
indistinguishable. The cost is that a missing file and a broken policy produce the identical error, and
whoever reads it goes and debugs IAM.

Measured, rather than assumed, by granting the permission two ways:

```
no ListBucket                    403  "not authorized to perform: s3:ListBucket"
ListBucket, prefix-conditioned   404  "The specified key does not exist"
ListBucket, unconditioned        404  "The specified key does not exist"
```

The prefix-conditioned form is enough, so the readable error costs no extra privilege — the function
still cannot enumerate anything outside `covers/`. It is in the provisioning script with that reasoning
attached, so the next person does not delete it as unused.

**A dead letter queue nobody watches is worse than none**, exactly as in 7d: it removes the *symptom*
along with the failure. Before it, a failing invocation retried visibly in the error metric; after it,
the function reports success and the event sits in a queue nobody has opened. So there is a CloudWatch
alarm at **threshold 0** — nothing consumes this queue, a human does, so its healthy depth is not "low",
it is empty.

Its honest limitation: SQS publishes `ApproximateNumberOfMessagesVisible` on a five-minute cadence, so
detection lags the failure by five to ten minutes. Adequate for covers; not adequate for anything a
customer is waiting on.

## The cost optimisation

Versioning makes a replaced cover recoverable and means the bucket **never shrinks** — every overwrite
keeps the previous bytes forever, invisibly, and you pay for all of them.

| rule | what it buys |
|---|---|
| `NoncurrentVersionExpiration` 30 days | The one that actually saves money. A cover replaced today is recoverable for a month, then the old bytes go. Without it, a cover updated monthly costs twelve covers a year and shows one. |
| `AbortIncompleteMultipartUpload` 7 days | The rule nobody sets and everybody eventually needs. Failed multipart parts are **billed** and **invisible** to `s3 ls` — which is why "why is this bucket 400 GB when it holds 2 GB" is such a common question. |
| Transition to `STANDARD_IA` at 90 days | ~45% cheaper for rarely-read objects. The catch is honest: IA charges for retrieval and has a 128 KB minimum billable size, so a bucket of 20 KB thumbnails read constantly costs **more** in IA than in Standard. |

Not applied deliberately: expiring **current** versions. A cover with no book is a bug; a book with no
cover after 400 days would be a feature nobody asked for.

## What got worse

- **The platform now has state AWS owns and this repository cannot recreate from code.** A dropped table
  loses browsing history with no migration to replay — Flyway's guarantee for PostgreSQL has no
  equivalent here, and the provisioning scripts describe *structure*, never contents.
- **Two more places for a failure to hide.** A Lambda that stops being triggered and a bounded queue
  that discards are both invisible from the API. The DLQ alarm covers one of them; nothing covers a
  bucket notification that somebody deletes.
- **The service now fails in ways its own tests cannot see.** Every AWS interaction is asserted against
  a mock, because a test that needs an AWS account is a test that does not run in CI. What that leaves
  unchecked is the part that broke most often here: whether the table actually exists with those keys,
  and whether the credentials in the environment can reach it.
- **Cost is now a property of correctness.** A hot partition, a missing TTL or a forgotten lifecycle
  rule does not fail — it bills. Nothing in this project alarms on spend.

## Next — Step 10

Eight services started by hand, in an order that matters, with a Kafka broker and four databases behind
them. Containers and Kubernetes.

---

# Step 10a — eight images, and what a Dockerfile is actually deciding

Nothing was containerised until now: `docker-compose.yml` held four databases and a broker, and the
eight JVMs were eight terminals. Each service has a `Dockerfile` next to its `pom.xml`, and

```bash
./scripts/build-images.sh
```

produces all eight. **8 seconds** with everything cached, **96 seconds** from a warm Maven cache and
cold layers.

`cover-processor` deliberately has none. It is a Lambda, deployed as a jar; giving it an image would
mean running a container to do the thing Lambda exists to do without one.

## Two stages, measured against the one-stage version

The naïve Dockerfile — one stage, a JDK, `java -jar` on the fat jar — was built as well, because
"multi-stage is smaller" is worth a number rather than a claim:

```
one stage,  JDK base, fat jar     691 MB
two stages, JRE base, layered     425 MB
```

**266 MB, 38%.** Almost all of it is the difference between `eclipse-temurin:21-jdk-alpine` (553 MB)
and `21-jre-alpine` (286 MB) — a compiler, `jcmd`, `jstack`, `javadoc` and the full module source,
shipped to production in the first version and absent from the second. The rest is the Maven
repository and the service's own source tree, which the one-stage image carries because everything a
build touches stays in its layers.

The security half of that is the half worth caring about: an image containing a compiler is an image
where anything that achieves execution can build and run whatever it likes.

## Layering, and the number that makes it matter

Spring Boot's layered format splits the fat jar by rate of change. A one-line change to a `.java` file,
rebuilt both ways, comparing which layer digests moved:

```
layered      1 layer changed      262 kB
fat jar      2 layers changed     74 MB + 291 kB
```

**262 kB against 74 MB**, for the same one-line change. The bytes inside the container are identical;
the layer is the unit Docker caches, pushes and pulls, so this is the difference between a deploy that
uploads a rounding error and one that uploads the whole dependency tree — every commit, per service,
eight times over. It is also why `COPY` order in the runtime stage runs dependencies first and
application last.

A detail worth keeping from the same experiment: the **first** attempt changed only a comment, and
*no layer moved at all*. `javac` discards comments, so the class files were byte-identical and the
application layer kept its digest. The fat-jar image changed anyway, because it ships the source. An
image that rebuilds identically from an unchanged program is a property, not a coincidence.

Sharing works across services too, since all eight share a base and several share dependency sets:

```
sum of the eight image sizes      3346 MB
actually on disk                  1348 MB unique + 211 MB shared base
```

## `-XX:MaxRAMPercentage=75` is a correctness fix, not tuning

This is the container trap that costs an afternoon, so it was measured rather than asserted. The same
image, in a 512 MB container, with and without the flag:

```
default                        MaxRAMPercentage 25    MaxHeapSize 134217728   (128 MiB)
-XX:MaxRAMPercentage=75        MaxRAMPercentage 75    MaxHeapSize 402653184   (384 MiB)
```

A modern JVM does read the cgroup limit — that part works. What it then does with it is the trap: it
takes **a quarter** of it. Give a service 512 MB and it runs a 128 MiB heap, GCs constantly, and
eventually throws `OutOfMemoryError` with three quarters of its memory unused. The symptom is a service
that is slow and then dies under exactly the load you provisioned for.

75 rather than 90 because heap is not the JVM's whole footprint: metaspace, thread stacks, the code
cache and direct byte buffers all live outside it, and a heap allowed to fill the cgroup gets the
process killed by the kernel rather than by the JVM. An OOMKill leaves no Java stack trace and looks,
from Kubernetes, exactly like a crash loop.

## Three smaller decisions in the same file

**Non-root**, verified rather than intended — `uid=100(app) gid=101(app)`. It also makes `/app`
effectively read-only to the application without any flag, since root owns it.

**The exec form of `ENTRYPOINT`.** With the shell form, PID 1 is `/bin/sh`, and `sh` does not forward
signals. Kubernetes sends `SIGTERM` and waits 30 seconds before `SIGKILL`, so the shell form turns
every rolling deploy into "all in-flight requests dropped" — with no error anywhere, because from the
outside a killed pod and a drained one look the same.

**`HEALTHCHECK` in the image, not only in compose.** An image that can say whether it is healthy is
what lets 10b express "start user-service *after* the config server is answering" rather than "start it
five seconds later". Kubernetes ignores `HEALTHCHECK` entirely and uses its own probes (10c) — so this
line is for compose, and duplicating it there would have put the same URL in two files.

## Why each service builds only itself

The build context is `bookstore-platform/`, because a module's pom inherits from the parent pom. But
only the parent pom and *one* module's sources are copied in:

```dockerfile
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY user-service/ user-service/
RUN ... ./mvnw -B -q -f user-service/pom.xml -DskipTests package
```

`COPY . .` would be one line shorter and would mean **an edit to analytics-service invalidates
user-service's build cache** — eight images rebuilt because one unrelated service changed.

`-f user-service/pom.xml` rather than the more usual `-pl user-service -am`: the aggregator lists nine
modules and refuses to build a reactor whose directories are missing, whereas resolving a parent by
`relativePath` needs only the parent pom. And `--mount=type=cache,target=/root/.m2` keeps the
dependency downloads out of the image entirely — they are already inside the jar — while making a
rebuild after a code change re-resolve nothing.

## The one thing in these files that could be wrong

**`-DskipTests`.** The tests need Testcontainers, which needs a Docker daemon inside the build. Running
them here means docker-in-docker for a suite that already runs green outside, and the honest cost is
stated rather than hidden: **nothing currently stops an image being built from code that fails its
tests.** Step 11's pipeline is where a failing test fails the build; until then "it built" means only
"it compiled".

## The two services whose Dockerfile is genuinely different

**config-server** copies `config-repo/` into the image and sets `CONFIG_REPO=file:/config-repo`,
because the `native` profile serves files from a directory and a container has no working directory two
levels up. That is a development shortcut and is labelled as one: an image is immutable, so a baked-in
config repo means changing a value requires rebuilding and redeploying the config server — the exact
property Step 6 existed to remove. The `git` profile is in the same image and is what a real deployment
runs.

It does **not** contain `ENCRYPT_KEY`, and never will. A master key in an image layer is a master key in
a registry, readable by anyone who can pull the tag, shipped next to the ciphertext it protects.

Proven end to end, with the key supplied at run time:

```
docker run -e ENCRYPT_KEY=... bookstore/config-server        healthy in 8s
GET /user-service/dev  ->  app.jwt.secret  w4HvWMieqVUX1LJpx/wVR+yANNMPgD/dwfEySBsQuZyr7nej+tEV7aZkrzB12Ip3
```

Decrypted from `{cipher}`, inside a container, by a server holding a key that is in no layer.

The same response shows exactly what 10b has to solve:

```
spring.datasource.url   jdbc:postgresql://localhost:5433/userdb
```

Inside a container `localhost` is the container. Every address on this platform is currently written
from a laptop's point of view.

**api-gateway** exposes two ports and health-checks the second one. 8080 is the public front door;
9090 serves actuator, where Step 8c moved it after `curl localhost:8080/actuator/env` returned the
platform's configuration on the one component facing the internet. A health check written against 8080
would get a 404 forever — there is no route for it, which *is* the property.

## Eight near-identical files, on purpose

Five of the eight Dockerfiles differ only in a module name, a port and a health URL. A single
parameterised build file taking `ARG SERVICE` would remove the duplication, and is not used, for the
reason a shared jar is not used (D12): **the shared file becomes something all eight services must
agree on.** api-gateway already needs a second port and a different health URL; config-server needs a
copied directory and an environment variable. Two of eight have escaped the template already, which is
the usual fate of build templates — and each escape in a shared file is a conditional that every other
service then carries. A service owns its own build (D29).

## What got worse

- **The images are built from untested code**, stated above and unfixed until Step 11.
- **`:latest` on eight images.** It is the tag that cannot be rolled back to, because it means something
  different tomorrow. Step 11 tags by commit SHA and this becomes a convenience alias.
- **Nothing runs yet.** Eight images that each start, fail to reach a config server on `localhost`, and
  exit. That is 10b.

---

# Step 10b — one command, and the three things that were written from a laptop's point of view

Eight terminals, started in an order that mattered, became:

```bash
docker compose up -d
```

**34 seconds to 13/13 healthy**, measured from a full `docker compose down`. Register, browse, order,
pay and both event consumers all work through the one published port.

## Only 8080 is published, and that is the point

Step 8 ended with a promise: *"In Step 10 a NetworkPolicy makes this the only way in for real."* Here
it is kept, and it needed no rule — only the absence of seven `ports:` lines.

```
from the host          8080 -> 200      8081, 8082, 8083, 8084, 8888, 9090 -> connection refused
from inside            order-service -> http://book-service:8082/api/books      200
                       api-gateway   -> http://localhost:9090/actuator/health   200
```

The services still verify every token themselves. Nothing about the trust model changed, and that is
deliberate: Step 8b's argument was that **a network boundary is not a security boundary until
something makes it one**, and compose networking is not that something. What this buys is that
bypassing the gateway stopped being something an outside client can *choose* to do.

The gateway's management port is the sharpest case. 9090 exists because Step 8c found
`/actuator/env` answering 200 on the internet-facing component. Not publishing it is the same
guarantee with nothing left to configure wrong.

## Kafka's advertised listeners, which is where a day goes

A Kafka client does not keep talking to the address it dialled. It bootstraps, and the broker replies
with metadata naming the address to use from then on — `advertised.listeners`. The old configuration
advertised `localhost:9092`, so a service in a container would connect to `kafka:9092` perfectly, be
told "the leader is at localhost:9092", reconnect **to itself**, and fail. A successful connection
followed by a timeout sends everybody to look at the network.

Two listeners, each advertised with the address that is true from where its caller sits:

```
INTERNAL   kafka:29092        the eight containers
HOST       localhost:9092     a service started with ../mvnw on the laptop
```

The laptop keeps 9092 on purpose. `KAFKA_BOOTSTRAP_SERVERS` defaults to `localhost:9092` in the config
repo and every CLI example in `test-platform.http` says 9092 — **giving the containers the new port
rather than the humans is the change that breaks nothing.** Verified from the broker, with both
consumers on container IPs and no lag:

```
analytics-service     bookstore.order.placed       partition 0  offset 1/1  lag 0  /172.21.0.12
notification-service  bookstore.payment.completed  partition 0  offset 1/1  lag 0  /172.21.0.11
```

## "dev" stopped meaning "on a laptop"

Every address in the dev profile was written from one machine's point of view, and inside a container
every one of them is wrong, because `localhost` is the container. Five files changed, all the same way:

```yaml
url: ${DB_URL:jdbc:postgresql://localhost:5433/userdb}
```

Overridable, with the laptop as the default — the idiom the four `*-prod.yml` files have used since
6d, minus the requirement to set it. The eight-terminal workflow needs no environment at all and is
untouched; compose supplies `DB_URL=jdbc:postgresql://user-db:5432/userdb`; 10c supplies the same
variable from a ConfigMap. The gateway's route table needed no edit whatsoever — it was already
written against `${app.services.*.url}` placeholders rather than addresses.

**The alternative was to change nothing**, because OS environment variables outrank config-server data
in Spring's precedence order: `SPRING_DATASOURCE_URL` in compose would simply have won. It is not done,
and the reason is the one this project keeps arriving at — the config repo would still read
`localhost:5433` while every real deployment ran somewhere else, and **nothing in the file would say it
was overridable**. Step 6's own "what got worse" already lists *"where does this value come from?"* as
the cost of a config server; silent overrides are that cost, doubled. See D31.

Only host and port vary. Credentials do not: they are that database's credentials wherever it runs, and
a service that can be handed a different username is a service that can be pointed at another service's
database.

## The bug the first bring-up found, in two halves

Four services died on `Connection to localhost:5433 refused` **with a correct `DB_URL` sitting in their
environment, unread**, because nothing was asking for it.

10a had baked `config-repo/` into the config-server image. The image was built before the placeholders
existed, so the server was serving the old hardcoded file — an immutable copy of configuration from
before the change. The Dockerfile comment predicted this in the abstract; the first bring-up after it
produced it for real, which is a fair summary of how that kind of comment usually ages.

The fix is a bind mount, and it is the shape 10c uses too:

```yaml
volumes:
  - ./bookstore-platform/config-repo:/config-repo:ro
```

The copy stays in the image so `docker run bookstore/config-server` works with nothing mounted; compose
mounts the working tree over it. **An image is immutable, so a baked-in config repo makes every
configuration change a rebuild and a redeploy — which is the exact property Step 6 existed to remove.**
In 10c the same directory arrives as a ConfigMap volume.

**Then the second half.** With the config server fixed and the four services healthy, `GET /api/books`
still returned 500 — a Netty connection failure inside the gateway. The gateway had started earlier and
fetched its configuration **once, at startup, from the broken server**, and was still routing to
`http://localhost:8082`.

That is not a compose quirk, it is the property Step 6c measured: configuration is read at startup and
does not re-read itself. A config server that changes while a client is running leaves that client on
the old values until something re-reads them — `/actuator/refresh`, or a restart. **Fixing a
configuration server does not fix the things already running against it**, and in 10c that becomes a
rolling restart.

## Memory limits, and the result that runs backwards

Every container has a limit, and the numbers come from running the stack without any and reading
`docker stats`:

```
no limits      3519 MiB across thirteen containers
with limits    2441 MiB, nothing above 54% of its own limit
```

**Adding limits made the platform use 30% less memory.** Not a cap taking effect — nothing was near a
limit. Without one, a container sees the *host's* memory, so 10a's `-XX:MaxRAMPercentage=75` sized each
heap against this laptop's 14.5 GiB. With a 640 MiB limit, book-service's JVM sizes itself to
`MaxHeapSize = 503316480` — 480 MiB — and a smaller maximum heap means a smaller resident set, because
the JVM stops deferring collection it has room to defer.

The corollary is the part worth keeping: **an unlimited container makes the same image behave
differently on every machine it lands on**, and the first place that shows up is a machine smaller than
the one it was tested on. A limit is what makes the JVM's own sizing deterministic, which is the entire
reason that flag exists.

| | |
|---|---|
| sum of limits | 5504 MiB |
| actual, idle | 2441 MiB |
| target host | t3.large, 8 GiB, minus ~800 MiB for k3s and the OS |

Which fits, with the honest caveat that this is an idle stack: limits are what the host must be able to
give up, and 5504 + 800 = 6.2 GiB of 8 leaves little for a load test.

These are limits and **not** reservations. Compose has no equivalent of Kubernetes `requests`, so
nothing here reserves memory or informs placement. The distinction arrives properly in 10c and is worth
being able to state: **requests decide scheduling, limits decide killing.**

## `depends_on` with a condition, not a sleep

```yaml
depends_on:
  config-server:
    condition: service_healthy
  user-db:
    condition: service_healthy
```

`service_started` would mean "the container exists", and a Postgres container that exists is not a
Postgres accepting connections — the difference is a Flyway migration failing on a cold start. This is
what 10a's `HEALTHCHECK` lines were for: the dependency is expressed as a condition rather than as a
guess about how long something takes.

The gateway depends on **only** the config server, deliberately. A gateway that refuses to start until
every backend is up cannot serve the half of the platform that is healthy, and turns one slow service
into a total outage. An unreachable route is a 503 on that route and nothing else.

## AWS credentials, and the worst secret handling on the platform

A container has no `~/.aws`, so the laptop's is mounted read-only rather than copied into a `.env`
beside the compose file — which is how a long-lived IAM credential ends up somewhere nobody is
watching. Where the directory does not exist, book-service starts, serves the catalog, and fails only
on the cover and history endpoints; knowing exactly which endpoints degrade is why it is written down.

This still hands a real IAM user's credentials to a process. 10c moves them into a Kubernetes Secret,
which is better and is not good either. **10d's IRSA is the actual answer, and what makes it the answer
is that it removes the long-lived credential rather than hiding it.**

## What got worse

- **`restart: unless-stopped` hides crash loops.** The four dead services showed as
  `Up 3 seconds (health: starting)` on every `docker compose ps` — a stack that looks like it is
  starting and has in fact been failing for ten minutes. `docker compose ps` should be read together
  with an uptime that keeps resetting, and 10c's `CrashLoopBackOff` says it out loud instead.
- **Two ways to run the platform, and only one of them is tested.** The eight-terminal workflow is kept
  working by the `${VAR:default}` defaults and by nothing else — no test covers it, and a compose-only
  change could break it silently.
- **The stack shares one Docker network with no policy on it.** Any container can reach any other,
  including four databases with fixed credentials. Compose has no NetworkPolicy; 10c does.
- **Still no end-to-end automated test.** The purchase flow above was run by hand, exactly as in 5a.
  Now that one command produces the whole platform, the excuse for that is thinner than it was.

---

# Step 10c — Kubernetes, and three probes that answer three different questions

Thirteen containers, as Deployments, StatefulSets, Services, a ConfigMap, a Secret and probes. The
manifests are in [k8s/](../k8s), developed against a one-node kind cluster and written to run unchanged
on the k3s box they are going to.

```
kind create cluster --config k8s/kind-cluster.yaml
./scripts/build-images.sh
./k8s/deploy.sh --load
```

**13/13 Ready, and register → browse → order → pay → both consumers all work through
`localhost:30080`.** The catalog starts empty and order ids start at 1, because the PersistentVolumes
are new — which is itself the check that Flyway ran from V1 inside the cluster.

## The prediction this step got wrong

The service manifests were written expecting `CrashLoopBackOff` to be the ordering mechanism. Kubernetes
has no `depends_on`; a controller reconciles towards a state rather than running a startup sequence, so
seven services should start before the config server is ready, fail fast, exit, and be restarted with
backoff until it answers.

Measured on the first deploy of all thirteen at once:

```
RESTARTS   0     (every pod, including all seven config-server clients)
```

What absorbed it was `spring.cloud.config.retry` — six attempts over about 31 seconds, added in **Step
6a** so that a laptop would not need its services launched in a particular order. The config server
answers inside that window, so the retry happens *inside one JVM* and the kubelet never sees a failure.
**A retry block written for a laptop is what stops the whole platform crash-looping on Kubernetes.**

CrashLoopBackOff remains the fallback if the config server takes longer than the budget. Both paths
converge; the only difference is whether the waiting happens inside the process or between restarts.
The comment in the manifest now says what happened rather than what was expected — 9d's rule, that a
measurement which does not measure what it was set up to measure is an open action rather than a
footnote, applies just as well to a measurement that comes back different from the guess.

## The liveness probe must not be `/actuator/health`

This is the most consequential line in the manifests, so it was demonstrated rather than argued.
`user-db` scaled to zero, then user-service inspected from inside its own pod:

```
/actuator/health             timed out          <- the datasource indicator BLOCKS
/actuator/health/liveness    {"status":"UP"}
/actuator/health/readiness   {"status":"UP"}
pod                          Ready, 0 restarts
POST /api/auth/login         504
```

Plain `/actuator/health` does not merely report DOWN — it **hangs**, waiting on a connection that will
never come. A liveness probe against it fails on `timeoutSeconds`, three times, and the kubelet deletes
a container with nothing wrong with it. Every replica, of every service sharing that database, in a
loop, for a problem no restart can fix — each restart discarding a connection pool that was seconds
from reconnecting.

With `user-db` scaled back:

```
same pod, still Ready, still 0 restarts, register -> 201
```

It recovered on its own. That outcome is what the probe split protects, and it is the argument for
`/actuator/health/liveness` in one measurement.

### Readiness deliberately does not check the database either

Spring's default readiness group contains only `readinessState`, and it would have been easy to "fix"
that by adding `db`. It is left alone on purpose: a readiness probe over a **shared** dependency removes
*every* replica from the Service's endpoints at the same moment, so callers get connection refused
instead of a 503 they can interpret, and one backend's problem becomes a total outage of everything in
front of it.

**Readiness answers "can this process serve", not "is the whole system well".** The two look alike right
up to the incident.

### And what a startupProbe is actually for

Kafka's readiness check was `kafka-broker-api-versions.sh`, which forks a JVM. With the default
`timeoutSeconds: 1`:

```
Readiness probe failed: command timed out after 1s   (x34 over 6m31s)
```

Raising the timeout fixes it and leaves a JVM starting every ten seconds forever on a two-core node. The
fix is structural: the expensive, truthful check becomes the **startupProbe**, which runs only until it
first succeeds and suspends the other two while it does; a cheap TCP check takes over afterwards. The
broker is proven to answer a real request once, and never pays for that proof again. Ready in **14
seconds**.

The same structure gives the Java services two independent budgets — 3 minutes to boot, 60 seconds to
notice a hang afterwards — instead of one liveness probe that has to be patient enough for the worst
cold start and therefore too patient to catch anything.

## The number that would not have fitted on the server

```
kubectl describe node

  cpu   2050m (12%)      <- 13 pods at 100m, plus the control plane's own ~950m
```

12% of a 16-core laptop, and **over 100% of a t3.large**, which has 2000m in total. The scheduler places
on *requests*, so the platform would not have fitted on its own deployment target: pods stuck `Pending`
with a message about insufficient cpu and nothing wrong with any container. Found only because the
number was read rather than assumed.

Requests came down to 50m per service and 25m per database — **600m for the whole platform**, the rest
being kind's kubeadm-style control plane, which k3s replaces with a single process.

**There are no CPU limits at all**, and that is a decision (D33). Memory is incompressible, so a
container over its limit must be killed; CPU is compressible, and a CPU limit does not kill, it
*throttles* — the cgroup gets its quota per 100 ms and then stops until the next period. That is worst
exactly at JVM startup, where class loading and JIT want every core for twenty seconds, so a modest
limit turns a 20-second start into minutes and can make a startupProbe give up on a healthy service.

| | requests | limits |
|---|---|---|
| cpu | 600m (platform) | none, deliberately |
| memory | 3658Mi incl. control plane | 5894Mi |

## Configuration, and the bug 10b hit twice

The config repo arrives as a ConfigMap volume — the same shape as 10b's bind mount, for the same
reason: an image containing configuration makes every configuration change a rebuild.

But a ConfigMap updated in place changes nothing on its own, because the process read those files at
startup and does not re-read them. That was 10b's bug, twice in one afternoon, and Kubernetes does not
fix it — `kubectl apply` on a ConfigMap leaves every pod exactly as it was.

So `deploy.sh` stamps a checksum of the config repo onto the pod template:

```
before                      config-server-56d8f67fb6-svfxd   b99fbcdea3ece689
expiration-minutes 60 -> 45
after                       config-server-5779d6cb65-8cvq2   270ca1441ec6ec2d
GET /user-service/dev  ->   "app.jwt.expiration-minutes": 45
```

A new pod, because a changed annotation is a changed template and a changed template is what a
Deployment rolls out. This is the standard idiom, and it is worth knowing that it is an idiom rather
than a feature: nothing in Kubernetes connects a ConfigMap to the workloads that mount it.

Addresses live in a second ConfigMap, and the key names deliberately differ from the variable names —
`USER_DB_URL` in the ConfigMap becomes `DB_URL` in user-service's container. `envFrom` would have been
shorter and would hand every service all four database URLs; the per-key `configMapKeyRef` means no
service holds the address of a database it must never touch.

**A Kubernetes Secret is base64 in etcd, not encryption.** `kubectl get secret -o yaml | base64 -d` is
the whole attack. What it does buy: the value stays out of Git, it is a separate RBAC resource so "can
deploy" and "can read credentials" can be different permissions, and rotating it does not rebuild an
image. What actually fixes it is not storing a long-lived credential at all — which for the AWS half is
10d's IRSA.

## StatefulSet for the databases, and it is not about replication

Each of these is one replica, so the reason is not scale. A Deployment treats its pods as
interchangeable and can start the new one before stopping the old during a rollout — two PostgreSQL
processes on one PersistentVolume, which is corruption rather than contention. A StatefulSet gives a
stable identity, a volume bound to that identity, and at-most-one semantics per ordinal.

The same argument is why Kafka is a StatefulSet, plus one more: a client must reach a *specific* broker,
the one leading its partition, so a load-balancing Service in front of brokers is wrong in a way that
looks fine with one broker and breaks with two.

What this is not: a production database. One replica, no backups, no failover, no PodDisruptionBudget,
on the same cluster as its clients. D26 already argues for RDS; the honest reason these are in-cluster
is that a capstone should start with one command for whoever clones it.

## Exposure, checked rather than assumed

```
localhost:30080   200
localhost:8081    refused          localhost:8888   refused
localhost:8082    refused          localhost:9090   refused

kubectl get svc api-gateway -o jsonpath -> http -> 8080        (9090 appears nowhere)
```

The gateway's management port is not in its Service, so actuator is unreachable from outside the pod by
construction — and `kubectl port-forward` still works for whoever legitimately needs it. Every other
service is `ClusterIP`, which is Step 8's promise enforced by the absence of anything else rather than
by a rule anyone could get wrong.

There is no Ingress, deliberately. api-gateway already **is** the edge; an ingress controller in front
of it would be a second front door with its own routing, its own CORS and its own place to get
authentication wrong — which is exactly what Step 8 spent a step removing.

## What got worse

- **Two descriptions of the same platform.** `docker-compose.yml` and `k8s/` both say what runs, with
  the same memory numbers and the same addresses written twice, and nothing checks that they agree. A
  Helm chart or Kustomize overlays would collapse them; both add a templating language to a project
  whose manifests are currently readable as-is, and the duplication is the price of that.
- **`:latest` on eight images plus `imagePullPolicy: Never`.** Together they mean "whatever was last
  loaded into this node", which is not a version and cannot be rolled back to. Step 11.
- **The Secret is base64 and the AWS credentials are long-lived.** Stated above; 10d fixes the second
  half and nothing here fixes the first.
- **One node, one replica of everything.** Nothing here has been shown to survive a node failure,
  because there is nowhere for anything to move to. `replicas: 2` is one word, and it is 10d's problem
  because 5d's recovery job and 7d's DLT monitor both assume they are the only instance.

---

# Step 10d — an autoscaler is a statelessness test, and it found the state

[`k8s/60-autoscaling.yaml`](../k8s/60-autoscaling.yaml) adds HPAs, and
[`docs/eks-and-irsa.md`](../docs/eks-and-irsa.md) is the EKS mapping. Both were the step's stated
challenge items; the interesting part was what applying them exposed.

metrics-server has to be installed on kind and is bundled with k3s — the first of several places where
"the same manifests everywhere" needed a footnote.

## It scales

40 concurrent loops against the gateway, 100 seconds in:

```
NAME            TARGETS           MINPODS  MAXPODS  REPLICAS
api-gateway     cpu: 1480%/70%    1        3        3
book-service    cpu: 2194%/70%    1        3        3
order-service   cpu:   15%/70%    1        2        2
user-service    cpu:   10%/70%    1        2        1
```

1 → 3 on both hot services, six pods `Running` with 0 restarts. The utilisation figures are percentages
**of the request**, not of the limit, which is why honest requests matter twice: once as what the
scheduler reserves, and again as the denominator the autoscaler divides by. A request padded "to be
safe" makes the ratio permanently small and the HPA never fires.

`scaleDown.stabilizationWindowSeconds: 300` against a 30-second scale-up, because a JVM costs about 20
seconds to replace. Flapping is expensive here in a way it is not for a Go binary.

## But adding replicas distributed nothing

order-service at 2 replicas, counting which pod actually did the work:

```
20 requests through the gateway     pod A 20   pod B 0
20 requests on fresh connections    pod A 12   pod B 8
```

**A Kubernetes Service load-balances connections, not requests.** kube-proxy picks a backend when the
TCP connection is established; every request on that connection then goes to the same pod for as long
as it lives. The gateway's Netty pool keeps connections alive indefinitely, so it chose one pod on its
first call and never reconsidered — and an HPA on top of that adds pods that receive nothing.

This is the most transferable thing in Step 10. It is invisible in every tutorial, because a tutorial
sends requests with `curl` and `curl` opens a new connection every time.

Bounding the connection lifetime fixes the symptom. `max-life-time: 10s` on the gateway's client pool,
40 requests spread over a minute:

```
pod A 19   pod B 21
```

It is a trade rather than a fix: shorter means better balance and more TCP handshakes, and it only
rebalances at that granularity. **The real answer is load balancing that understands requests** — a
service mesh sidecar, an L7 proxy per service, or client-side load balancing over a discovery client.
All three are larger than this platform, and naming them is the honest end of the argument.

## Which services can scale, and the two that measurably cannot

Every service was examined rather than assumed, and two failed.

**analytics-service is broken at 2 replicas, with no failure involved at all.** Scaled to two, seven
orders placed:

```
pod A   TALLY: 2 order(s), revenue 99.98
pod B   TALLY: 4 order(s), revenue 199.96
```

Kafka splits the partitions between the two consumers, each pod tallies only what it consumed, and
**both numbers are wrong**. Nothing errors, nothing warns, and a dashboard reading either one is simply
lying. The tally lives in a JVM heap, which 7c already listed as outstanding; scaling is what turns
that from a durability note into a correctness bug.

**notification-service is broken for the same reason**, one step less visibly: its "have I already
confirmed order 17?" guard is a bounded in-memory set, so a redelivery landing on a different replica
sends the customer a second email. 7c stated the rule this violates — *an idempotency guard must be at
least as durable as the effect it guards* — and a second replica is a second, emptier guard.

The four that do scale, and why:

| | |
|---|---|
| **api-gateway** | Step 8's three rules — no business logic, no aggregation, no stored state — are exactly the statelessness an HPA needs. That rule was written to keep the gateway honest, and this is where it pays. |
| **user-service** | a token is a signature, not a session |
| **book-service** | reads its own database; the history writer is fire-and-forget into a per-pod executor |
| **order-service** | request path is stateless; the recovery job duplicates work but not effect |

**The recovery job caveat, which 5d named Step 10 as the deadline for.** `OrderRecoveryJob` runs on
every replica, so two replicas sweep the same stuck orders. That is *safe* rather than merely
tolerated: 5d made release idempotent precisely so a repeat is a no-op, and two racing transactions are
settled by a primary key. The cost is duplicated scanning, not incorrectness — and payment-service is
left at one replica anyway, because `PaymentRecoveryJob` never gives up by design and N replicas means
N processes sweeping forever, in the one service where wasted work is somebody's money.

**The general shape:** a service is horizontally scalable when its replicas share nothing, and the
state that stops them is almost never the database. It is a cache, a counter, a scheduled job, or an
in-memory idempotency guard. Two of eight here, both event consumers, both keeping their state in the
heap.

## The incident this step caused, which is the most realistic thing in it

Adding the connection-pool setting introduced a **duplicate `httpclient` key** into
`config-repo/api-gateway.yml` — the file already had one, forty lines further down. What happened next
is worth the whole sub-step:

- the config server returned **500** for `/api-gateway/dev`;
- the **running** gateway pod carried on serving perfectly, because it read its configuration at
  startup and never again;
- and the two pods the **HPA had just created** could not start.

So a broken configuration change was invisible until something needed to restart — and the thing that
needed to restart was the autoscaler responding to load. **The failure surfaced at peak traffic, in new
pods, while the old ones looked healthy.** A deployment would have found it too; an autoscaler finds it
at the worst possible moment and without a human present.

Two things follow, and both were fixed rather than noted:

1. **Nothing validates the config repo.** `ConfigServerContractTest` asserts what the files *say*; it
   did not catch a file that will not parse, because a duplicate key is legal YAML to some parsers and
   fatal to SnakeYAML's strict mode. A test that simply loads every file in `config-repo/` would have
   caught this at build time, and that is Step 11's list.
2. **`deploy.sh` stamped the config checksum on config-server only.** Every other service reads that
   configuration once at startup too, so a changed value reached the server and none of its seven
   clients — the 10b bug, reintroduced by my own deploy script. All eight pod templates carry the
   annotation now.

## What got worse

- **The platform now has a load-balancing story that only works because of a timeout.** `max-life-time`
  is a workaround with a number in it, and the number is a guess about how long an imbalance is
  tolerable.
- **Two services are pinned at one replica by in-memory state**, and nothing enforces that. Somebody
  scaling analytics-service gets wrong numbers and no error; a comment in a YAML file is the only
  guard.
- **The HPA cannot help on one node.** Autoscaling pods without autoscaling nodes is bounded by the
  machine, and on a t3.large the ceiling arrives quickly. Karpenter is the EKS answer and is in the
  design note, not in this repository.
- **The config repo can still be broken in a way nothing catches until a pod restarts.** Fixed for the
  one file that broke; unvalidated in general.

---

# Step 11a — a test gate, and the config file nothing was checking

[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) runs the whole reactor on every push and
pull request. Since 10a every Dockerfile has run `package -DskipTests` (D30), so **"it built" meant
only "it compiled"** — 11b's image job `needs:` this one, and that is where the sentence stops being
true.

The tests are not adapted to run in CI. A GitHub-hosted runner has a Docker daemon, so Testcontainers
and `@EmbeddedKafka` work unchanged and the pipeline runs the same suite a laptop does. **A suite
needing a CI-only variant would be proving something different from the one developers run.**

## The config file nothing was checking

`ConfigRepoParsesTest` closes the gap 10d found the hard way, and it is a twenty-line test for a
failure that took down every pod that restarted.

`ConfigServerContractTest` could not have caught it, and the reason is worth stating: that test asserts
what the files **say** — which service gets which datasource, which route points where — and to do so
it asks a running config server, which by then has already failed to load the file. The new test
asserts only that they can be **read at all**, which is both cheaper and more fundamental.

Verified by reintroducing the bug rather than by trusting it:

```
clean repo                          20 files, all pass
a real duplicate `httpclient` key   Tests run: 20, Failures: 1
                                    [config-repo/api-gateway.yml must be loadable YAML]
```

A `@TestFactory` rather than a loop, so the broken file is **named** instead of being "the first one
that threw" — Surefire renders a dynamic test as `everyConfigFileParses()[4]`, and SnakeYAML's own
error says `in 'reader', line 14` without a filename, so the assertion carries the name itself.

It also asserts it found at least fifteen files. A `Files.list` pointed at a moved directory would
produce zero dynamic tests and report **green**, which is this test's own failure mode one level up.

## A CI defect fixed before it could be one

Every `.sh` in the repository, and `mvnw` itself, was mode **100644** in the git index. Windows does not
track the execute bit, so `./mvnw` on a Linux runner fails with `Permission denied` — a red build with
nothing wrong with the code. `git update-index --chmod=+x` on all nine.

---

# Step 11b — a version you can roll back to

[`.github/workflows/cd.yml`](../../.github/workflows/cd.yml) builds the eight images and publishes them
to GHCR, tagged by commit SHA.

## Two tags, and only one of them is a version

```
ghcr.io/<owner>/bookstore/<service>:<git-sha>     immutable; names one build forever
ghcr.io/<owner>/bookstore/<service>:latest        a convenience alias, and a different thing tomorrow
```

`:latest` is the one tag that **cannot be rolled back to**, which is exactly what Step 10's manifests
used and said so. The SHA tag is what makes "which build is in production" and `kubectl rollout undo`
answerable at all.

A matrix over the eight services rather than one job looping: eight images build in parallel, and the
job name alone says *which* image failed. `fail-fast: false`, so the seven that work still publish.

**GHCR rather than ECR**, and the reason is the same argument as `docs/eks-and-irsa.md`: Actions
authenticates to GHCR with the `GITHUB_TOKEN` it already has, so there is **no secret to create, store
or rotate**. ECR needs either a long-lived IAM user key in a repository secret or an OIDC role. Both
cost pennies; only one of them adds a credential. Swapping is the registry prefix plus a
`configure-aws-credentials` step.

`deploy.sh` grew a registry mode to match — `IMAGE_REPO` and `IMAGE_TAG` rewrite the image line and
flip `imagePullPolicy: Never` to `IfNotPresent`. A `sed`, not a templating language: `kubectl apply -f
k8s/` still works with no preprocessing, and that property is worth more here than what Helm would add.

## The rollback path, demonstrated rather than promised

A deliberately broken deploy — an image tag that does not exist:

```
kubectl set image deployment/user-service user-service=bookstore/user-service:v99-broken

  rollout status              error: timed out waiting for the condition   (exit 1)
  new pod                     0/1   ErrImageNeverPull
  old pod                     1/1   Running
  POST /api/auth/register     201        <- during the failed deploy
```

**The platform never stopped serving.** That is not luck: a Deployment removes the old pod only once
the new one passes its readinessProbe, so a rollout that cannot start is a rollout that changes
nothing. 10c's probe work is what buys this, and it is why a readinessProbe that lies is worse than
none.

Then the undo:

```
kubectl rollout undo deployment/user-service

  deployment "user-service" successfully rolled out
  image                       bookstore/user-service:latest
  POST /api/auth/register     201
```

The previous ReplicaSet still existed and still named the previous image, which is the whole point of
an immutable tag: **rolling back is finding a tag, not rebuilding a commit.** `cd.yml` does exactly
this automatically — `rollout status` per deployment, and `rollout undo` on any that fail to converge.

## The deploy stage is gated, and deliberately not wired to a cluster

The assignment allows an automated deploy **or** a deploy stage designed with a manual approval gate.
This is the second, and the reason is not effort: reaching the k3s box means either exposing its API
server to GitHub's runners or running a self-hosted runner inside it. **Opening a Kubernetes API server
to the internet to make a capstone demo tidier is a bad trade**, and it would sit in this repository as
an example for somebody to copy.

`environment: production` is what makes the gate real rather than a comment — with a required reviewer
on that environment, GitHub will not run the job until a human approves, and the kubeconfig secret is
scoped to the environment so nothing outside an approved deployment can read it.

---

# Step 11c — what to alarm on, which is a harder question than how to collect

Actuator has collected these metrics since Step 6c and Micrometer has been the facade over them the
whole time. 11c adds a wire format and something to read it, and **no application code changed** —
which is the property worth noticing: swapping Prometheus for CloudWatch or Datadog is one dependency,
because nothing in this platform references a registry type.

```
Prometheus   http://localhost:30090        Grafana   http://localhost:30300
```

Prometheus discovers targets by asking the Kubernetes API rather than from a list of eight addresses.
The autoscaler creates and destroys pods; a static list would monitor the pods that existed when
somebody wrote it and silently stop covering whatever scaled up — **which is the moment monitoring
matters most.**

Measured, with all eight scraping:

```
sum by (application) (rate(http_server_requests_seconds_count[5m]))

  book-service    0.541      api-gateway     0.361      user-service    0.218
  order-service   0.214      analytics       0.218      payment         0.218

histogram_quantile(0.99, sum by (application,le) (rate(http_server_requests_seconds_bucket[5m])))

  api-gateway  0.68s     book-service  0.21s     order-service  0.043s     user-service  0.019s

sum by (application,status,uri) (http_server_requests_seconds_count{status!="200"})

  book-service   404  /api/books/{id}   11
  order-service  409  /api/orders        8        <- out of stock, correctly refused
  api-gateway    404  NOT_FOUND         11
```

## p99 is why `percentiles-histogram` and not `percentiles`

The distinction is easy to get wrong and impossible to fix afterwards. `percentiles:` has each process
compute its own 99th percentile and publish the answer — and **those numbers cannot be aggregated**:
the p99 of three replicas is not the mean, the max, or any function of their three p99s. With an
autoscaler adding and removing replicas, a per-instance percentile is a number about one pod that
nobody wants.

`percentiles-histogram: true` publishes cumulative **bucket counts** instead. Counts add across
replicas, so `histogram_quantile` over summed buckets is a true platform-wide p99. The cost is series
count, which is why it is on for HTTP requests and nothing else, with explicit SLO boundaries chosen
from what this platform does — the top bucket sits above the 3 s Feign read timeout, or every timeout
lands in `+Inf` and the quantile becomes a guess.

## Seven alerts, and the rule that chose them

**Alert on symptoms the customer feels, not on causes.** High CPU is a cause and is usually fine;
requests failing is a symptom and never is. Every rule below would wake somebody, which is the test for
whether it should exist — there is a much longer list of things worth *watching* than alerting on.

| | why this one |
|---|---|
| `HighErrorRate` | 5xx share > 5%. A **ratio**, so it means the same at 10 req/s and 10,000. 5xx only: 4xx is customers mistyping, and alerting on it pages every time a token expires |
| `HighLatencyP99` | > 3s. A mean hides the tail completely — 99 fast requests and one 10-second request average out fine, and the customer who waited 10 seconds is the one who leaves |
| `KafkaConsumerLag` | The one metric that catches a failure with **no error anywhere**. A stopped consumer is UP, has a zero error rate, and orders quietly go unconfirmed — Step 7 described exactly this and the answer was "read the logs" |
| `ConnectionPoolExhausted` | Hikari *pending* is threads blocked waiting for a connection: the shape of every "slow and nothing is erroring" incident, and the leading indicator of the N+1 queries Step 2 hunted |
| `CircuitBreakerOpen` | Step 5c said "a breaker whose state nobody can see converts an outage into a mystery". This is that sentence kept |
| `DeadLetterMessages` | Threshold **zero**. Nothing consumes those topics, a human does, so healthy depth is not "low", it is empty — 7d's argument as a rule |
| `PodRestartLoop` | Everything above measures a process that is running. **This is the alert that would have fired during the 10d incident**, where new pods could not start while the old ones served perfectly and every other metric stayed green |

Deliberately **not** alerted on, because every unnecessary alert makes the necessary ones less likely
to be read: CPU and memory utilisation (causes, and the HPA already responds to CPU); request rate (a
drop to zero at 3am is normal and at noon is an outage — that needs seasonality, not a threshold); JVM
heap (GC exists; alert on OOMKills, which `PodRestartLoop` catches).

**Routing is designed, not deployed.** Alertmanager would take these and fan out to SNS or a Slack
webhook, with `severity: page` and `severity: ticket` as the routing key — the labels are already on
every rule. Adding Alertmanager is one Deployment and a receiver config; what it needs and this project
does not have is somebody to page.

## Three bugs this step produced, all the same shape

**1. Five services 404ed on an endpoint their configuration plainly enabled.** Six files override
`management.endpoints.web.exposure.include`, and a `grep -A3 exposure` found only two of them because
comments sat between the key and its value. The config-move rule this project has learned repeatedly —
**grep the leaf key, not the dotted path** — and it was broken again by grepping the *parent* key with
too little context.

**2. Then they still 404ed after the fix**, and this one is a genuine race in `deploy.sh`. Applying all
eight workloads at once rolls the config server and its seven clients **simultaneously**, so a client
can start, fetch from the *outgoing* config-server pod, and come up holding the previous configuration
— permanently, while reporting Ready and carrying the correct checksum annotation. The symptom was five
services 404ing on an endpoint the config server was demonstrably serving correctly, and the fix for
each was a restart that changed nothing else.

`deploy.sh` now rolls the config server first and waits for it. **The thing that serves configuration
is itself a process that must be current before anything reads it** — 10b's lesson one level up.

**3. `/actuator/prometheus` returned 500 rather than 404 on order-service**, because its
`GlobalExceptionHandler` treats `NoResourceFoundException` as unhandled. A missing path is a 404; a 500
is the server claiming a bug it does not have and sending whoever reads the log to debug the wrong
service — which is Step 5b's argument, arriving again in a service that had already made it.

## What got worse

- **Metrics die with the pod.** One Prometheus replica, `emptyDir`, six hours of retention, no
  Alertmanager and no long-term storage. kube-prometheus-stack is the real answer and is a Helm chart
  with ~40 CRDs, which would teach Helm rather than monitoring. The alert **rules** are the part worth
  having and the part that transfers to any backend unchanged.
- **`/actuator/prometheus` is `permitAll`.** The boundary is the Service definition rather than the
  filter chain — no service's port is published outside the cluster — but metrics disclose URI
  templates and error rates, and there is still no NetworkPolicy in this namespace. Same gap 10c
  listed, not a new one.
- **`PodRestartLoop` needs kube-state-metrics**, which is not installed, so the one alert that would
  have caught the 10d incident is the one that cannot currently fire.
- **The gateway's error rate cannot be attributed to a route.** Spring Cloud Gateway reports `uri` as
  `UNKNOWN` or `NOT_FOUND` because it has no URI templates, so "which route is failing" needs its own
  `gateway_requests_seconds` metrics rather than the HTTP server ones.
