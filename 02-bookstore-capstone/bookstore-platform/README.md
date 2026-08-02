# bookstore-platform — the services (Steps 5–11)

The monolith, split. It is preserved in Git at the `step-4-monolith` tag:

```bash
git checkout step-4-monolith
```

## Status

| Service | Port | Database | State |
|---------|------|----------|-------|
| `config-server` | 8888 | none | ☑ 6a |
| `user-service` | 8081 | `userdb` on 5433 | ☑ 5a |
| `book-service` | 8082 | `bookdb` on 5434 | ☑ 5a |
| `order-service` | 8083 | `orderdb` on 5435 | ☑ 5b |
| `payment-service` | 8084 | `paymentdb` on 5436 | ☑ 5e |

## Run it

```bash
docker compose up -d
```

(from `02-bookstore-capstone/` — starts **four** PostgreSQL instances)

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

Then work through [test-platform.http](test-platform.http), which exercises the boundary itself: a token
minted on 8081, accepted on 8082.

Everything at once:

```bash
./mvnw test
```

99 tests across the five modules. Testcontainers supplies the databases, and no test talks to the
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

## Next — Step 7

Placing an order still does everything synchronously, so the customer waits for every side effect and
each new one couples another service to the order path. Kafka.
