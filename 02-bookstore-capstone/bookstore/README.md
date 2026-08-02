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

Tests currently need PostgreSQL running — Step 4 removes that with Testcontainers.

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

## 2c–2e — still to do

The N+1 problem, reproduced then fixed · a justified index plus `EXPLAIN ANALYZE` · `@Version`
optimistic locking under concurrent writes.
