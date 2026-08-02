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
| `payment-service` | 8084 | `paymentdb` | 5d |

## Run it

```bash
docker compose up -d
```

(from `02-bookstore-capstone/` — starts **three** PostgreSQL instances)

```bash
cd user-service && ../mvnw spring-boot:run
```

```bash
cd book-service && ../mvnw spring-boot:run
```

```bash
cd order-service && ../mvnw spring-boot:run
```

Then work through [test-platform.http](test-platform.http), which exercises the boundary itself: a token
minted on 8081, accepted on 8082.

Everything at once:

```bash
./mvnw test
```

71 tests across the three services. Testcontainers supplies the databases, so nothing needs to be running.

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

## Next — 5c

Resilience4j: a circuit breaker that stops calling a service that is clearly down, a retry for
transient blips, and a fallback so browsing degrades instead of erroring.
