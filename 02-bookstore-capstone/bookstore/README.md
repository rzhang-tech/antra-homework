# bookstore — the monolith (Steps 1–4)

One Spring Boot application. Steps 1–4 build it up; Step 5 splits it into microservices.

## Step 1 — what was built and why

### Layers, built inside-out

```
Client → BookController (HTTP) → BookService (rules) → BookRepository (data) → H2
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

| Profile | Database | DDL | Notes |
|---------|----------|-----|-------|
| `dev` (default) | H2 in-memory | `create-drop` | seeded from `data.sql`, SQL logging on, H2 console on |
| `prod` | from `${DB_URL}` etc. | `validate` | no credentials in the file; Hibernate may never alter the schema |

`prod` uses `ddl-auto: validate` on purpose — letting Hibernate mutate a production schema is how data
gets lost. Migrations own the DDL from Step 2 onward.

`spring.jpa.open-in-view` is `false` globally: the default (`true`) keeps the persistence session open
through view rendering, which silently allows lazy loading in the controller and hides N+1 problems.
Turning it off makes those mistakes fail loudly, which matters before Step 2's N+1 exercise.

## Run

```bash
./mvnw spring-boot:run
```

`http://localhost:8080`, dev profile, five seeded books. H2 console at `/h2-console`
(JDBC URL `jdbc:h2:mem:bookstore`, user `sa`, no password).

```bash
./mvnw test
```

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

## Next — Step 2

Swap H2 for PostgreSQL in Docker, add the `Author` entity and the `Book → Author` relation, put the
schema under Flyway, add a justified index, create and then fix an N+1 query, and show the `EXPLAIN
ANALYZE` plan.
