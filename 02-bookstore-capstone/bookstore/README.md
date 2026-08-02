# bookstore — the monolith (Steps 1–4)

One Spring Boot application. Steps 1–4 build it up; Step 5 splits it into microservices.

## Step 1 — what was built and why

### Layers, built inside-out

```
Client → BookController (HTTP) → BookService (rules) → BookRepository (data) → PostgreSQL
              ↑                        ↑
   GlobalExceptionHandler        LoggingAspect (@Around)
```

Each layer has one responsibility, so each has one reason to change. Because the outer layer depends on
the inner one, they are built inside-out — entity, then repository, then service, then controller. Every
layer is complete before anything depends on it.

The dependency direction is also why the controller holds no business rules. "A duplicate ISBN is a
conflict" is a rule about books, not about HTTP; it lives in the service. That is what lets the entire
service layer move into `book-service` in Step 5 without being rewritten.

### Constructor injection, not `@Autowired` fields

`BookServiceImpl` takes its `BookRepository` through the constructor (`@RequiredArgsConstructor` over a
`final` field). Three consequences: the dependency is immutable, it is impossible to construct the
object in a half-wired state, and a unit test can pass in a mock with no Spring context at all.

### DTOs at the boundary

Controllers speak `BookRequestDto` / `BookResponseDto`; the `Book` entity never leaves the service layer.
See [D3](../docs/decisions.md) for the three reasons — security, coupling, serialization.

`PageResponseDto` exists for the same reason applied to pagination: Spring's `Page` serializes fine, but
its JSON shape is a framework detail that has changed between versions. Pinning the envelope keeps that
out of the public contract.

### One error shape, one place

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps every exception to the same `ErrorResponse`
envelope:

| Exception | Status | Body |
|-----------|--------|------|
| `ResourceNotFoundException` | 404 | message + path |
| `DuplicateResourceException` | 409 | message + path |
| `MethodArgumentNotValidException` | 400 | plus `fieldErrors` map |
| anything else | 500 | generic message; the stack trace is logged, never returned |

Without it, each controller method needs its own try/catch and any unhandled exception leaks a stack
trace to the client.

### AOP for the cross-cutting concern

`LoggingAspect` logs every service method's name, arguments, and elapsed time from a single `@Around`
advice. Written inline this would be the same six lines in every method, with business logic buried in
instrumentation.

Sample output from a real run — note that the failure path is logged too, and the exception still
propagates to the handler:

```
DEBUG ... LoggingAspect : -> BookServiceImpl.findById(..) args=[1]
INFO  ... LoggingAspect : <- BookServiceImpl.findById(..) completed in 6 ms
DEBUG ... LoggingAspect : -> BookServiceImpl.create(..) args=[BookRequestDto[title=Refactoring, ...]]
WARN  ... LoggingAspect : !! BookServiceImpl.create(..) failed after 2 ms: DuplicateResourceException: ...
```

**The limitation worth knowing:** Spring AOP is proxy-based. Advice only fires on calls that arrive
through the proxy, so a service method calling `this.otherMethod()` is not logged — the same reason
`@Transactional` does not apply to self-invocation.

### Transactions

Reads are `@Transactional(readOnly = true)`; writes are `@Transactional`. `update()` deliberately has no
`save()` call: inside a transaction the loaded entity is *managed*, so Hibernate detects the changed
fields and flushes them on commit. That is dirty checking, and it is why an accidental setter on a
managed entity is a real write.

### Profiles

| Profile | Database | DDL | Flyway locations | Notes |
|---------|----------|-----|------------------|-------|
| `dev` (default) | PostgreSQL on `localhost:5432` | `validate` | `db/migration` + `db/seed` | SQL logging on, demo books loaded |
| `prod` | from `${DB_URL}` etc. | `validate` | `db/migration` only | no credentials in the file; no demo data |

Both profiles use `ddl-auto: validate` — Hibernate may only check that the entities match the tables, and
the app refuses to start if they have drifted. Flyway owns every schema change. See
[D6](../docs/decisions.md) for why, and [D7](../docs/decisions.md) for why the demo rows are separated
out by location rather than by convention.

`spring.jpa.open-in-view` is `false` globally: the default (`true`) keeps the persistence session open
through view rendering, which silently allows lazy loading in the controller and hides N+1 problems.
Turning it off makes those mistakes fail loudly, which matters before Step 2's N+1 exercise.

## Run

```bash
docker compose up -d
```

(from `02-bookstore-capstone/` — starts PostgreSQL 17)

```bash
./mvnw spring-boot:run
```

`http://localhost:8080`, dev profile. Inspect the database directly with:

```bash
docker exec -it bookstore-postgres psql -U bookstore -d bookstore
```

```bash
./mvnw test
```

Tests need nothing running: Testcontainers starts its own PostgreSQL and disposes of it (Step 4b).

## Verified behaviour

All of these were run against the live app:

| Case | Result |
|------|--------|
| `GET /api/books?size=3` | 200, paged envelope, `totalElements: 5` |
| `GET /api/books?keyword=java` | 200, 1 match (case-insensitive) |
| `GET /api/books/1` | 200 |
| `GET /api/books/9999` | 404 + error envelope |
| `POST /api/books` | 201 + `Location: /api/books/6` |
| `POST` with a duplicate ISBN | 409 |
| `POST` with `title:""`, `price:-5`, `stock:-1` | 400 + all three field errors |
| `PUT /api/books/1` | 200, changes persisted |
| `DELETE /api/books/5` then again | 204, then 404 |

See [test.http](test.http) to re-run them.

---

# Step 2 — Data Layer

Split into five parts so each new concept lands on its own.

## 2a — PostgreSQL + Flyway ☑

Swapped the in-memory H2 for PostgreSQL 17 in Docker, and moved schema ownership from Hibernate to
Flyway. **No Java code changed** — the point of the step is that replacing the storage engine should not
touch business logic.

| Change | File |
|--------|------|
| PostgreSQL with a named volume | [`../docker-compose.yml`](../docker-compose.yml) |
| The schema, by hand | `src/main/resources/db/migration/V1__init.sql` |
| Demo rows, dev only | `src/main/resources/db/seed/R__dev_sample_books.sql` |
| Driver + Flyway; H2 removed | `pom.xml` |
| Datasource, `ddl-auto: validate`, Flyway locations | `application-dev.yml`, `application-prod.yml` |

**What the first boot prints:**

```
Creating Schema History table "public"."flyway_schema_history"
Migrating schema "public" to version "1 - init"
Migrating schema "public" with repeatable migration "dev sample books"
Successfully applied 2 migrations to schema "public", now at version v1
```

**The second boot:**

```
Current version of schema "public": 1
Schema "public" is up to date. No migration necessary.
```

**And the point of the whole step** — a book created before a restart is still there afterwards. Under
Step 1's H2 it would have been gone.

The schema also gained a database-level constraint that the DTO validation cannot replace:

```sql
CONSTRAINT book_stock_non_negative CHECK (stock >= 0)
```

Bean Validation gives the user a good error message; the CHECK constraint guarantees the data is never
wrong, including against a bad migration, a manual `UPDATE`, or another service writing to the same
table after Step 5. Both layers earn their place.

Two design notes worth reading before Step 2b: [D6](../docs/decisions.md) (why Flyway rather than
`ddl-auto`) and [D7](../docs/decisions.md) (why the seed file is repeatable, written up after the
original versioned attempt broke the migration ordering).

## 2b — the Author relation ☑

`V2__add_author.sql` creates the `author` table and gives `book` a nullable `author_id` with a foreign
key. Nullable is not laziness: a `NOT NULL` column cannot be added to a table that already has rows
without either a default or a backfill, and books genuinely may have no author on record.

| File | Change |
|------|--------|
| `entity/Author.java` | new — id + name, one-directional for now |
| `entity/Book.java` | `@ManyToOne(fetch = LAZY) @JoinColumn(name = "author_id")` |
| `repository/AuthorRepository.java` | new |
| `dto/BookRequestDto.java` | `authorId` (optional) |
| `dto/BookResponseDto.java` | `authorId` + `authorName`, flattened |
| `service/BookServiceImpl.java` | `resolveAuthor(...)` — null is allowed, an unknown id is a 404 |
| `db/seed/R__dev_sample_books.sql` | five authors, linked to the demo books |

**Why the request takes an `authorId` and not a nested author object.** Creating a book must not
silently create an author. An id says "attach this book to an author that already exists"; a nested
object invites the API to guess whether to insert, update, or match by name.

**Why `Author` has no `List<Book>` yet.** A one-directional relation is enough to model "a book has an
author," and every relation you add costs something (serialization recursion, cascade semantics,
`equals`/`hashCode` care). The back-reference arrives in 2c, when listing authors with their books is
what creates the N+1 problem that step is about.

**The N+1 is already visible.** `LAZY` means the author row is fetched only when someone reads the
field — and `BookResponseDto.from` reads it for every book. One `GET /api/books?size=5` now issues:

```
select ... from book b1_0 order by b1_0.id offset ? rows fetch first ? rows only
select count(b1_0.id) from book b1_0
select a1_0.id, a1_0.name from author a1_0 where a1_0.id=?    -- binding [5]
select a1_0.id, a1_0.name from author a1_0 where a1_0.id=?    -- binding [2]
select a1_0.id, a1_0.name from author a1_0 where a1_0.id=?    -- binding [4]
select a1_0.id, a1_0.name from author a1_0 where a1_0.id=?    -- binding [1]
select a1_0.id, a1_0.name from author a1_0 where a1_0.id=?    -- binding [3]
```

Seven queries for five books: one for the page, one for the count, and **one per book** for the author.
Ask for 100 books and it is 102 queries. Step 2c measures this properly and fixes it.

## 2c — the N+1 problem, reproduced and fixed ☑

### Measured, not asserted

`GET /api/authors?naive=true` deliberately takes the unoptimised path. Same data, same endpoint, one
query parameter apart:

| Request | Queries | What it does |
|---------|---------|--------------|
| `GET /api/authors?naive=true` | **6** | 1 for the authors + 1 per author for their books |
| `GET /api/authors` | **1** | one `LEFT JOIN FETCH` |
| `GET /api/books?size=5` | **2** (was 7) | page + count; the author now rides along on the join |
| `GET /api/books/1` | **1** (was 2) | |

Five authors makes it 6 versus 1. Five hundred authors makes it 501 versus 1 — and the code looks
identical either way, which is what makes N+1 dangerous. Nothing in `AuthorResponseDto.from` says
"query the database"; it just reads `author.getBooks()`.

### The two fixes, and why they are different

**Book → author (to-one): `@EntityGraph`.**

```java
@EntityGraph(attributePaths = "author")
@Override
Page<Book> findAll(Pageable pageable);
```

Declarative, composes with derived queries and with `Pageable`. Joining a to-one association cannot
multiply rows, so `LIMIT`/`OFFSET` still counts books and paging stays correct.

**Author → books (to-many): explicit `LEFT JOIN FETCH`.**

```java
@Query("SELECT DISTINCT a FROM Author a LEFT JOIN FETCH a.books ORDER BY a.id")
List<Author> findAllWithBooks();
```

- `LEFT` so an author with no books still appears.
- `DISTINCT` because the join multiplies rows — an author with three books returns three rows, and
  without it Hibernate hands back the same `Author` three times. It de-duplicates the *entities*; the
  database rows are still there.
- **Returns a `List`, not a `Page`, on purpose.** Fetching a collection and paginating cannot both
  happen in SQL: `LIMIT` would apply to joined rows, cutting an author's books in half. Hibernate
  detects this and silently loads *every* row to paginate in memory, warning
  `HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory` — fine on
  five authors, an outage on five hundred thousand. When you genuinely need both, use `@BatchSize` or
  two queries (page the ids, then fetch collections for that page).

### Why not just make it EAGER

The reflex fix is `fetch = FetchType.EAGER`, and it is the wrong one. `EAGER` means *always* join —
including on the many queries that never touch the association — so it trades N+1 for permanent
over-fetching, and it interacts badly with paging. The correct shape is what is here: **`LAZY` by
default, fetched explicitly by the queries that need it.** Per-query decision, not a global one.

### A bug this exercise caught

Comparing the naive and fetch-join responses byte for byte, they disagreed — same authors, same books,
**different order**. Neither query had an `ORDER BY`, so PostgreSQL returned rows in whatever order suited
it, and the two plans happened to differ. Both paths now order explicitly by id. A result set without
`ORDER BY` has no guaranteed order, and "it looked sorted in testing" is not a contract.

## 2d — indexes and query plans ☑

### Getting a measurable baseline first

On the seven demo rows every plan is a `Seq Scan`, and correctly so: the whole table fits in one page,
so reading an index and then going back to the heap is strictly more work. Nothing about indexing can be
demonstrated at that size.

[`scripts/load-benchmark-data.sql`](../scripts/load-benchmark-data.sql) loads 2,000 authors and 100,000
books. It is **not** a Flyway migration — it is slow, it is noise in the schema history, and it must
never run outside a local benchmark database. Rows are tagged `BENCH-` so they can be dropped again.

The distribution matters as much as the volume. A first attempt spread 100k books over the five demo
authors, giving each 20% of the table — and at that selectivity PostgreSQL *correctly refuses* an index,
because reading a fifth of the rows through one costs more than scanning the table. 2,000 authors puts
each at ~0.05%, which is what a foreign-key filter looks like in production.

### Before and after

| Query | Before | After | Plan after |
|-------|--------|-------|------------|
| `WHERE author_id = ?` (50 of 100k) | 8.5 ms | **0.55 ms** | `Bitmap Index Scan on idx_book_author_id` |
| `WHERE title = ?` | 6.0 ms | **0.07 ms** | `Index Scan using idx_book_title` |
| `WHERE lower(title) LIKE '%zebra%'` | 19.8 ms | **0.14 ms** | `Bitmap Index Scan on idx_book_title_trgm` |

### The three indexes, and why each earns its place

**`idx_book_author_id`** — PostgreSQL indexes a PRIMARY KEY automatically but **not** a FOREIGN KEY
column, which surprises people. This column is filtered on, joined on, and read by the referential
check that runs on every `DELETE FROM author`.

**`idx_book_title`** — serves exact matches and prefix searches (`LIKE 'Clean%'`). A B-tree is ordered,
so it can seek to a known prefix.

**`idx_book_title_trgm`** — a B-tree cannot help `LIKE '%keyword%'`. It is sorted by the *start* of the
string, and a leading wildcard means there is no known start. `pg_trgm` indexes every three-character
sequence instead, turning a substring match into a lookup.

### The bug this step caught

The trigram index was built on `lower(title)`. The application's search was
`findByTitleContainingIgnoreCase`, and Spring Data generates `UPPER(title) LIKE UPPER(?)` for
`IgnoreCase`. **An expression index is only used when the query's expression matches it character for
character**, so the real query ignored the index completely:

```
upper(title) LIKE upper('%Zebra%')   Seq Scan             21.9 ms
lower(title) LIKE lower('%Zebra%')   Bitmap Index Scan     0.175 ms
```

The index existed, looked correct in `pg_indexes`, and did nothing — with no warning anywhere. Nothing
short of running `EXPLAIN` on the query the *application* sends would have found it.

The fix is `BookRepository.searchByTitle`, an explicit `@Query` using `LOWER`, so the SQL and the index
are under one author's control and visibly aligned. Building the index on `upper(title)` instead would
also work, but leaves it depending on a Spring Data code-generation detail that a version upgrade could
change.

Verified against the application's exact generated SQL, join and `ESCAPE` clause included:

```
Limit
  -> Sort  Sort Key: b1_0.id
    -> Hash Left Join  Hash Cond: (b1_0.author_id = a1_0.id)
      -> Bitmap Heap Scan on book b1_0
        -> Bitmap Index Scan on idx_book_title_trgm
             Index Cond: (lower((title)::text) ~~ '%zebra%'::text)
Execution Time: 0.951 ms
```

### What indexes cost

Inserting the same 20,000 rows, measured on this database:

| | Time |
|---|---|
| With all three indexes | 369.8 ms |
| With none | 84.3 ms |

**4.4× slower writes**, plus disk. That is the trade every index makes, and why "add an index" is a
decision rather than a reflex — an index nothing queries is pure cost.

## 2e — optimistic locking under real concurrency ☑

`Book` has carried `@Version` since Step 1, but nothing in the API ever performed a read-modify-write,
so the column never did anything. `POST /api/books/{id}/purchase` is the first operation that reads
stock, checks it, and writes it back — and therefore the first that two clients can race.

### Why `@Transactional` alone is not enough

A transaction makes the three steps atomic against a crash. It does **not** stop two concurrent
transactions from both reading `stock = 20` and both writing `19`. Under PostgreSQL's default
READ COMMITTED isolation that is permitted, and the result is a **lost update**: two copies sold, one
deducted.

`@Version` closes it. Hibernate does not write a bare `UPDATE`; it writes:

```sql
update book set author_id=?, cover_url=?, isbn=?, price=?, stock=?, title=?, version=?
where id=? and version=?
```

The second transaction to commit matches **zero rows**, because the first already moved `version`.
Hibernate raises `ObjectOptimisticLockingFailureException`, the transaction rolls back, and the client
gets 409.

### Measured

[`scripts/concurrent-purchase.mjs`](../scripts/concurrent-purchase.mjs) creates a book with stock 30 and
fires 30 single-copy purchases through `Promise.all`:

```
HTTP status tally: { '200': 5, '409': 25 }
final stock = 25
expected    = 30 - 5 = 25
OK — no lost updates: every successful purchase is accounted for in the stock.
```

The arithmetic is the assertion. 5 succeeded, 25 lost the race, and stock fell by exactly 5. Without
`@Version` the successes would outnumber the deductions and the two figures would diverge.

A first attempt at this demo used 19 backgrounded `curl` processes and produced 19 successes and zero
conflicts — process startup is far slower than the transaction, so the requests never overlapped. It
looked like proof that the locking was unnecessary. `Promise.all` in a single process was needed to make
the requests genuinely simultaneous.

### Optimistic, not pessimistic

The alternative is `SELECT ... FOR UPDATE`, which locks the row so the second transaction waits. That is
the right tool when conflicts are *common* — the loser waits a moment instead of failing. Here conflicts
on any one book are rare, so a pessimistic lock would serialise every purchase of that book whether or
not anyone was competing. Optimistic locking costs nothing in the common case and only makes the loser
retry.

### The Step 1 race condition, now closed

The Step 1 review flagged that `create()` does a check-then-act — `existsByIsbn(...)` then `save(...)` —
so two concurrent requests with the same ISBN could both pass the check. The database's unique
constraint caught the second, but as `DataIntegrityViolationException`, which no handler mapped: the
client saw **500** instead of 409.

`GlobalExceptionHandler` now maps it. Verified with 20 concurrent creates of the same ISBN:

```
{ '201': 1, '409': 19 }        rows actually in the database: 1
```

The application-level check is still worth keeping — it produces a precise message ("A book with isbn X
already exists") in the common case. The handler covers the narrow window the check cannot close. Both
layers, same as the `CHECK (stock >= 0)` constraint in 2a: the application explains, the database
guarantees.

---

# Step 2 complete

| Part | Delivered |
|------|-----------|
| 2a | PostgreSQL in Docker, schema owned by Flyway, data survives restarts |
| 2b | `Author` entity, `Book -> Author` relation |
| 2c | N+1 reproduced and fixed — 6 queries to 1, 7 to 2 |
| 2d | Three justified indexes, `EXPLAIN ANALYZE` against the application's real SQL |
| 2e | `@Version` optimistic locking proven under 30-way concurrency |

---

# Step 3 — Authentication & Security

Split into three parts: storing users safely, issuing and checking tokens, then enforcing roles.

## 3a — users, registration, BCrypt ☑

No JWT yet. This part answers one question only: **how does a password get stored?**

| File | Purpose |
|------|---------|
| `V4__add_users.sql` | `users` table — `CHECK (role IN ('USER','ADMIN'))`, unique username and email |
| `entity/Role.java` | `USER` / `ADMIN` enum |
| `entity/User.java` | the entity; the field is `passwordHash`, never `password` |
| `repository/UserRepository.java` | `findByUsername`, `existsBy*` |
| `dto/RegisterRequestDto.java` | validated input, with a masked `toString` |
| `dto/UserResponseDto.java` | output — no hash, ever |
| `config/PasswordEncoderConfig.java` | `BCryptPasswordEncoder(10)` |
| `service/UserServiceImpl.java` | hashes the password, assigns the role |
| `controller/AuthController.java` | `POST /api/auth/register` |
| `security/SecurityConfig.java` | stateless chain; all routes still open until 3c |

### Why BCrypt and not SHA-256

SHA-256 is built to be **fast**, which is precisely wrong for passwords: a modern GPU computes billions
per second, so a stolen table of SHA-256 hashes is a dictionary attack away from being a table of
passwords. BCrypt is deliberately slow, and its cost is tunable — hardware improvements are answered by
raising the work factor rather than by changing algorithm.

The 158 ms this endpoint takes is not a performance problem; it is the feature.

It also salts automatically. Two users registering the same password:

```
alice  $2a$10$6vzzr38wasaS3vAlMziJIuvRACVZK..Yl94WjaYCL5tn5nIXIHJFe
bob    $2a$10$K.SIMAHkJSQEHyA6nIJ0Tu5tkA2ZsP/.FuVPn8W7adhkP8r1hlIl6
```

Different hashes. That defeats rainbow tables and stops the database revealing who shares a password
with whom. The salt lives inside the string, so no separate column is needed:

```
$2a$10$N9qo8uLOickgx2ZMRZoMye IjZAgcfl7p92ldGxad68LJZdL17lhWy
 │   │  └──── 22-char salt ──┘ └──────── 31-char hash ───────┘
 │   └── cost: 2^10 rounds
 └────── algorithm version
```

### The security bug this step nearly shipped

`LoggingAspect` logs every service method's arguments, and a record's generated `toString` includes
every component. `UserServiceImpl.register(RegisterRequestDto)` would therefore have written **the
plaintext password** into the application log at DEBUG — from where it reaches log aggregation,
backups, and support tickets.

Fixed at the source by overriding `toString` on the DTO, so no caller anywhere can print it:

```
-> UserServiceImpl.register(..) args=[RegisterRequestDto[username=ruoyu, email=ruoyu@example.com, password=****]]
```

Verified by grepping the whole log for the password used in testing: no match.

### Two more things the request deliberately does not accept

**`role`.** If a client could send it, anyone could register as ADMIN. The server assigns `USER`.

**A password longer than 72 characters.** BCrypt only reads the first 72 bytes, so longer passwords are
silently truncated and two sharing a 72-byte prefix become the same password. Rejecting them is honest;
accepting them quietly is not.

### `EnumType.STRING`, not the default

`@Enumerated(EnumType.ORDINAL)` — the default — stores the enum's *position*: `USER` as 0, `ADMIN` as 1.
Inserting a constant into the middle of the enum later would silently reassign every existing row's
role. On a privilege level, that is a security bug that leaves no trace in the data.

### Verified

| Case | Result |
|------|--------|
| `POST /api/auth/register` | 201, hash stored as `$2a$10$...` |
| duplicate username | 409 |
| duplicate email | 409 |
| short password / bad email / short username | 400 with all three field errors |
| plaintext password in logs | none |
| same password, two users | different hashes |

## 3b — JWT: issued on login, checked on every request ☑

| File | Purpose |
|------|---------|
| `config/JwtProperties.java` | `app.jwt.*`, validated at startup |
| `security/JwtUtil.java` | mint and verify tokens |
| `security/CustomUserDetailsService.java` | loads the user for **login only** |
| `security/JwtAuthenticationFilter.java` | header → SecurityContext, once per request |
| `security/SecurityErrorWriter.java` | 401/403 in the same JSON envelope as everything else |
| `security/SecurityConfig.java` | stateless chain, filter placement, `AuthenticationManager` |
| `dto/LoginRequestDto`, `LoginResponseDto` | in and out |

### What a token actually is

Three base64url segments joined by dots. Decoding the one this server issued:

```
header    { "alg": "HS512" }
payload   { "sub": "ruoyu", "role": "USER", "iss": "bookstore",
            "iat": 1785639122, "exp": 1785642722 }
signature bjgHOvC79n8uZ8yMDWUW5BQOPQDj_1Fok...   (86 chars)
```

**The first two segments are encoded, not encrypted.** Anyone holding the token can read them — the
decode above used nothing but base64. A JWT protects *integrity*, not confidentiality: the signature
proves the claims have not been altered since this server signed them. Nothing secret goes in a token.

That is also why no session store is needed. The server does not remember issuing the token; it
recomputes the signature from the payload with its own key, and a match proves authenticity. Any
instance can verify any token, which is the precondition for scaling out and for the Step 8 gateway.

### Proven, not assumed

| Attack | Result |
|--------|--------|
| Valid token on `/api/auth/me` | 200 |
| No token | 401 |
| **Payload edited to `"role": "ADMIN"`, signature untouched** | **401** |
| Invented token (`not.a.token`) | 401 |
| Correct username, wrong password | 401 `"Invalid username or password"` |
| Username that does not exist | 401 — **byte-identical response** |

The third row is the one that matters. Changing `role` to `ADMIN` in the payload is trivial — but the
signature no longer matches the modified payload, and the request is rejected. That is the entire
security model in one test.

### The username oracle, and timing

Login returns one message for both failures. Distinguishing "no such user" from "wrong password" hands
an attacker a list of real accounts to concentrate on.

Response time can leak the same thing: if a missing user returns instantly while a real one costs a
100 ms BCrypt comparison, the message no longer matters. `DaoAuthenticationProvider` hashes a dummy
password when the user is absent, specifically to close that channel. Measured over 8 attempts each:

```
existing user, wrong password : 74.6 ms
user that does not exist      : 69.2 ms
```

Close enough to carry no signal. Hand-rolled `if (user == null) return 401;` would have shown the
difference plainly — which is why `AuthenticationManager` does the comparison rather than our own code.

### Where the secret lives

| Profile | Value |
|---------|-------|
| `dev` | a literal in `application-dev.yml`, committed knowingly — it signs tokens for a throwaway local database, and its presence means `clone && docker compose up && mvnw spring-boot:run` needs no setup |
| `prod` | `${JWT_SECRET}` with **no default** — the application refuses to start without it |

The absence of a production default is deliberate. A service that quietly falls back to a known signing
key issues tokens anyone can forge, and nothing about it looks broken.

`JwtProperties` is `@Validated`, so a missing or under-length secret fails at startup rather than on the
first login. HS256 needs 256 bits of key material; the constraint enforces it in characters.

### Two design decisions worth defending

**The filter never rejects anything.** A missing, malformed, or expired token leaves the context empty
and the request continues as anonymous. The authorization rules then decide — 401 on a protected route,
200 on a public one. Rejecting inside the filter would break every public endpoint for anyone carrying
an expired token.

**Identity is built from the token's claims, with no database lookup.** That is what makes the design
stateless, and it is what will let the Step 8 gateway validate tokens with no database at all. The cost
is staleness: a user deleted or demoted keeps whatever the token says until it expires. Short expiry is
the mitigation; a revocation list is the real answer if one is ever needed.

### 401 versus 403

Worth stating precisely, because they are routinely confused:

- **401 Unauthorized** — "I do not know who you are." No token, or an invalid one. Authenticating may help.
- **403 Forbidden** — "I know who you are, and you may not do this." Valid token, wrong role. Re-authenticating changes nothing.

`SecurityErrorWriter` implements both, because these rejections happen inside the filter chain and never
reach `@RestControllerAdvice` — without it, Spring Security returns an empty body for exactly the two
failures a client hits most.

## 3c — role-based authorization ☑

### The matrix, verified end to end

Every endpoint against all three identities:

| Method | Path | anonymous | USER | ADMIN |
|--------|------|-----------|------|-------|
| GET | `/api/books` | 200 | 200 | 200 |
| GET | `/api/books/{id}` | 200 | 200 | 200 |
| GET | `/api/authors` | 200 | 200 | 200 |
| GET | `/api/auth/me` | **401** | 200 | 200 |
| POST | `/api/books/{id}/purchase` | **401** | 200 | 200 |
| POST | `/api/books` | **401** | **403** | 201 |
| PUT | `/api/books/{id}` | **401** | **403** | 200 |
| DELETE | `/api/books/{id}` | **401** | **403** | 204 |
| GET | `/api/whatever` (undeclared) | **401** | — | — |

403 comes back in the same envelope as every other error:

```json
{"timestamp":"...","status":403,"error":"Forbidden",
 "message":"You do not have permission to perform this action.","path":"/api/books"}
```

### Rules in one place, not scattered across annotations

All of it lives in a single `authorizeHttpRequests` block. Authorization spread over dozens of
`@PreAuthorize` annotations cannot be reviewed as a whole, and "which endpoints are public?" stops being
a question anyone can answer by reading. Method security is the right tool for rules that depend on the
*data* — "only the owner of this order" — which arrives in Step 5.

Rules are evaluated top to bottom and **the first match wins**, so specific patterns must precede
general ones. Misordering is silent: a broad rule placed early quietly swallows the narrow ones below.

### Deny by default

The last rule is `.anyRequest().authenticated()`, not `permitAll()`. A route added later is therefore
closed until someone deliberately opens it, rather than public until someone notices. `GET /api/whatever`
returning 401 is that policy working.

### Roles are not hierarchical

`ADMIN` does not "include" `USER` in Spring Security. `hasRole("USER")` on the purchase endpoint would
have given an admin a **403**, which reads as a bug and is really a misunderstanding. Both roles are
named explicitly:

```java
.requestMatchers(HttpMethod.POST, "/api/books/*/purchase").hasAnyRole("USER", "ADMIN")
```

A `RoleHierarchy` bean can establish `ADMIN > USER` if that is genuinely wanted; being explicit is
clearer while there are two roles.

### Bootstrapping the first admin

Registration cannot create an ADMIN — that is the point of leaving `role` out of the request. So the
first one has to be seeded: `db/seed/R__dev_admin_user.sql`, dev profile only, `admin` /
`admin-dev-password`. The BCrypt hash in that file is real but publishing it costs nothing — the
password is in the comment beside it and the account exists only in a throwaway local database. Real
credentials come from the environment.

### The bug the matrix caught

`POST /api/books/{id}/purchase` and `PUT /api/books/{id}` returned **500** for authorized users:

```
NullPointerException: Cannot invoke "java.lang.Long.longValue()" because "current" is null
```

`V1__init.sql` declared `version BIGINT` with no default. Hibernate sets the initial value for entities
it creates, so books added through the API were fine — but the dev seed inserts with plain SQL, and
those rows landed with `version = NULL`. The first `UPDATE` of such a row then failed on the version
increment.

It survived Step 2e's 30-way concurrency testing precisely because that test created its book through
the API. Only a write to a *seeded* book could trigger it, and nothing had done that until the
authorization matrix exercised every endpoint against real rows.

Fixed forward in `V5__book_version_not_null.sql` — backfill, then `SET DEFAULT 0`, then `SET NOT NULL`.
V1 has already run elsewhere and must not be edited (D8). The `DEFAULT` matters most: it stops any
future direct `INSERT` from recreating the same landmine.

---

# Step 3 complete

| Part | Delivered |
|------|-----------|
| 3a | `users` table, registration, BCrypt hashing, no plaintext anywhere |
| 3b | JWT issued on login, verified per request, proven against tampering |
| 3c | Public / USER / ADMIN enforced on every endpoint, deny by default |

---

# Step 4 — Testing

Deliberately before Step 5's refactor. Tests written *after* a refactor only prove what the refactor
produced; written before, they prove it did not change behaviour.

## 4a — unit tests ☑

**22 tests, 1.7 seconds, no Spring context and no database.**

| File | Covers |
|------|--------|
| `service/BookServiceImplTest.java` | 15 tests — find, create, update, purchase, delete |
| `service/UserServiceImplTest.java` | 7 tests — registration and login |

`@ExtendWith(MockitoExtension.class)` builds the mocks and injects them; the service under test is a
plain object. That speed is the point — these run on every save, so they must never wait on
infrastructure.

They also express situations a real database makes awkward. "The repository claims this ISBN exists" is
one stubbed line here; arranging it for real means inserting a row first.

### What they assert that matters

- **`update` never calls `save`.** The absence is the behaviour, not an omission —
  `verify(bookRepository, never()).save(any())` pins dirty checking in place, so a later "fix" that adds
  a `save()` call has to be a deliberate decision.
- **`update` uses `existsByIsbnAndIdNot`, never `existsByIsbn`.** The plain check would match the book
  being edited and make it permanently un-editable — a bug that only appears when you re-save a book
  without changing its ISBN.
- **An unknown `authorId` throws instead of silently saving `null`.**
- **A duplicate username is rejected before `passwordEncoder.encode` is ever called** — no point paying
  100 ms of BCrypt to then throw it away.
- **The plaintext password never reaches the saved entity.**
- **Failed login issues no token:** `verify(jwtUtil, never()).generate(any())`.

### Proving the tests have teeth

A green suite proves nothing on its own. Changing one character in the stock check —
`book.getStock() < quantity` to `< 0` — produces:

```
[ERROR] BookServiceImplTest.refusesOverselling <<< FAILURE!
[ERROR] Tests run: 15, Failures: 1
[INFO] BUILD FAILURE
```

The assignment's Definition of Done asks that broken logic fail a test. This is that, demonstrated
rather than claimed.

### What unit tests cannot do

A mock will happily agree with a query that PostgreSQL would reject. These tests say nothing about
whether the SQL is valid, whether the entity mapping matches the schema, or whether the transaction
commits. That is what 4b and 4c are for.

## 4b — slice tests on a real database ☑

**44 tests, 21.8 seconds — and no PostgreSQL running beforehand.**

```bash
docker compose stop     # nothing listening on 5432
./mvnw test             # Tests run: 44, Failures: 0, Errors: 0 — BUILD SUCCESS
```

That is the headline change. Until now `./mvnw test` required someone to have run `docker compose up -d`
first, an instruction that works for a person at a keyboard and not at all for CI.

### Testcontainers, in two lines

```java
@Bean
@ServiceConnection
PostgreSQLContainer<?> postgresContainer() {
    return new PostgreSQLContainer<>("postgres:17-alpine");
}
```

`@ServiceConnection` reads the container's host, port, database and credentials once it is up and wires
the DataSource from them — no `@DynamicPropertySource`, no properties to keep in step with the compose
file. The image tag matches `docker-compose.yml` on purpose: testing against a different database than
you deploy on is testing something else.

The container starts in ~2 s and is reused across test classes that share a context. Cleanup is
automatic — a `ryuk` sidecar reaps everything when the JVM exits.

### `BookRepositoryTest` — 9 tests, `@DataJpaTest`

The slice loads entities, repositories and a transaction manager; no controllers, no security, no
services. Every test rolls back afterwards.

`@AutoConfigureTestDatabase(replace = NONE)` is essential — by default this slice swaps in an embedded
database, which would undo the entire point.

What it proves that a mock cannot:

- **The Flyway schema matches the entity mapping.** With `ddl-auto: validate`, a column renamed in a
  migration but not in the entity fails the context. Reaching the first assertion is the result.
- **`searchByTitle` is really case-insensitive and really matches mid-word** — against PostgreSQL's
  collation, not Mockito's opinion.
- **The unique constraint on `isbn` fires**, and **`CHECK (stock >= 0)` rejects negative stock even when
  the service is bypassed entirely**. That is the argument for two layers, demonstrated.
- **`version` defaults to 0 for a row inserted with raw SQL** — the V5 regression, pinned so it cannot
  come back.
- **The `@EntityGraph` really joins the author**: the test detaches everything with `entityManager
  .clear()` and then reads `book.getAuthor().getName()`. A lazy proxy would throw
  `LazyInitializationException`; it does not.

`entityManager.flush()` then `clear()` in the fixture is not ceremony. Without the clear, reads come
back from Hibernate's first-level cache, and a broken query still "passes" by returning the object the
test just put in memory.

**A mistake worth keeping in the notes:** the two constraint tests first wrapped only `flush()` and
failed. With `GenerationType.IDENTITY` Hibernate cannot defer the INSERT — it needs the generated key
immediately — so the constraint fires inside `save()`. Under a sequence generator the original version
would have worked. The tests now wrap save-and-flush together, so they assert on the constraint rather
than on the id strategy.

### `BookControllerTest` — 12 tests, `@WebMvcTest`

Spring MVC and nothing else; the service is a `@MockitoBean`. These are about the HTTP contract: given
that the service returns X or throws Y, what does the client see?

| Group | Asserts |
|-------|---------|
| public reads | 200 and the JSON shape — including that `version` is **not** in the response |
| authorization | anonymous 401, USER 403, ADMIN 201/204 — and `verify(never())` that a rejected request never reaches the service |
| validation | 400 naming every failed field, service never invoked |
| domain errors | 409 for duplicate ISBN and for insufficient stock, with the numbers preserved |
| unexpected errors | 500 whose body does **not** contain the internal message |

The security classes are imported explicitly. Without them the slice runs Spring Security's defaults
rather than ours, and the authorization assertions would be testing the framework instead of this
application.

Two details that cost time and are worth writing down:

- **`with(csrf())` on every mutating request.** The real chain disables CSRF — it is a token-authenticated
  API — but MockMvc's security setup applies it anyway, and a missing token shows up as a 403 that looks
  exactly like an authorization failure.
- **`UserDetailsService` and `PasswordEncoder` are mocked** purely so `SecurityConfig`'s
  `AuthenticationManager` bean can be constructed. Nothing exercises them: `@WithMockUser` supplies the
  identity directly, which is what keeps these tests about *authorization* rather than login.

## 4c — end-to-end integration ☑

**6 tests, nothing mocked.** Real HTTP on a real servlet port, through the real security filter chain,
into the real services, against the Testcontainers PostgreSQL.

That is the point of having them. The unit tests mock the repository; the web slice mocks the service.
Each proves its own layer and neither proves the layers fit together — that a token minted by
`/api/auth/login` is accepted by `/api/books/{id}/purchase`, and that the resulting decrement actually
lands in PostgreSQL, can only be established by doing all of it.

| Test | Proves |
|------|--------|
| `fullCustomerJourney` | register → browse anonymously → login → `/me` with the token → purchase → **stock in the database is 17, not just in the response** |
| `anonymousPurchaseChangesNothing` | 401, and stock still 20 |
| `tamperedTokenIsRejected` | payload rewritten to `"ADMIN"`, signature untouched → 401 over real HTTP |
| `roleRulesHoldOverRealHttp` | same request: customer 403, admin 201 |
| `overbuyingIsRejected` | 409 with `"only 20"`, stock unchanged |
| `concurrentPurchasesNeverOversell` | 20 simultaneous purchases; no lost update, no 500, stock never negative |

### The concurrency test asserts invariants, not counts

How many of the 20 requests conflict depends on thread timing, so asserting "5 succeed" would be a
flaky test. What must hold every time:

```java
assertThat(succeeded + conflicted).isEqualTo(attempts);        // no other outcome — in particular no 500
assertThat(stockInDatabase()).isEqualTo(20 - succeeded);       // no lost update
assertThat(stockInDatabase()).isNotNegative();                 // never oversold
```

Step 2e proved the same property by hand with a Node script. This runs it in CI, on every commit,
forever. The application log during the run is full of
`Optimistic lock conflict on POST /api/books/1/purchase` — the lock is genuinely being exercised, not
merely present.

### Two things about `@SpringBootTest` with a real port

**It does not roll back.** The server handles each request on its own thread in its own transaction, so
the test's transaction has nothing to undo. Every test therefore creates its own fixture with a unique
ISBN rather than assuming a clean database.

**Named for purchase, not orders.** The assignment lists `OrderFlowIntegrationTest`, but orders do not
exist until Step 5. Purchase is the same shape — authenticate, mutate stock transactionally, observe the
result — and the class gets renamed when there is a real order flow to cover.

---

# Step 4 complete

**50 tests, 26 seconds, one command, nothing running beforehand.**

```bash
./mvnw test     # Tests run: 50, Failures: 0, Errors: 0 — BUILD SUCCESS
```

| Layer | Count | Speed | Mocks | Answers |
|-------|-------|-------|-------|---------|
| Unit (`@ExtendWith(MockitoExtension)`) | 22 | ms | repository | are the business rules right? |
| Repository (`@DataJpaTest`) | 9 | ~8 s | none | is the SQL valid and does the schema match? |
| Web (`@WebMvcTest`) | 12 | ~4 s | service | is the HTTP contract right? |
| Integration (`@SpringBootTest`) | 6 | ~12 s | none | do the layers actually fit together? |
| Smoke | 1 | — | none | does the context start? |

The shape is deliberate: many fast tests that pin down logic, few slow ones that pin down integration.
Inverting it produces a suite nobody runs.

## Next — Step 5

Split the monolith into `user-service`, `book-service`, `order-service` and `payment-service`, each with
its own database. A method call that could not fail becomes a network call that can; a transaction that
spanned two tables now spans two databases with no shared ACID guarantee. OpenFeign with timeouts,
Resilience4j circuit breakers, and identity propagated across service boundaries.

These 50 tests exist so that refactor can be checked rather than hoped about.
