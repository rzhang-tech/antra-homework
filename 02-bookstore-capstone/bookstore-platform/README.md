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
| `order-service` | 8083 | `orderdb` | 5b |
| `payment-service` | 8084 | `paymentdb` | 5d |

## Run it

```bash
docker compose up -d
```

(from `02-bookstore-capstone/` — starts **two** PostgreSQL instances)

```bash
cd user-service && ../mvnw spring-boot:run
```

```bash
cd book-service && ../mvnw spring-boot:run
```

Then work through [test-platform.http](test-platform.http), which exercises the boundary itself: a token
minted on 8081, accepted on 8082.

Everything at once:

```bash
./mvnw test
```

57 tests across both services. Testcontainers supplies the databases, so nothing needs to be running.

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

## Next — 5b

`order-service`: placing an order requires knowing a book's price and stock, which now live behind an
HTTP call that can fail. OpenFeign with an explicit timeout, and the user's identity propagated across
the hop.
