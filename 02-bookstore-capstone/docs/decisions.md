# Design Decisions

A running log of the non-obvious choices, and the reasoning behind them. These are the questions an
interviewer asks about this project, so the answer lives here rather than only in the code.

---

## D1 — Spring Boot 3.5.x + Spring Cloud 2025.0.x, not Boot 4.x

**Decision.** Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.3.

**Why.** Spring Cloud release trains are pinned to a Spring Boot generation. The 2025.0.x train ships
Cloud modules 4.3.x (Gateway, Config, OpenFeign, CircuitBreaker), which target Boot 3.5.x. This project
depends on five different Spring Cloud modules, so the release train has to line up — picking the newest
Boot would leave half the Cloud stack unusable or undocumented. Java 21 is the current LTS and is what
the runtime here provides.

**Trade-off.** Not the absolute latest Boot. Acceptable: nothing in the assignment needs a Boot 4 feature.

---

## D2 — Build the monolith first, then split it

**Decision.** Steps 1–4 produce one Spring Boot application. Step 5 splits it into services.

**Why.** This mirrors how real systems evolve and how the assignment is graded. Splitting a working
monolith teaches what a service boundary actually costs: a method call that could not fail becomes a
network call that can, and a transaction that spanned two tables now spans two databases with no shared
ACID guarantee. Starting microservices-first hides that lesson — you would inherit the solutions
(Feign, circuit breakers, sagas) without ever meeting the problem.

**Trade-off.** Some Step 1–4 code gets moved and rewritten in Step 5. That rework *is* the exercise.

---

## D3 — DTOs at the API boundary, entities never leave the service layer

**Decision.** Controllers accept `*RequestDto` and return `*ResponseDto`. JPA entities stay internal.

**Why.** Three separate reasons, and it is worth being able to name all three:

1. **Security** — an entity has fields the client must not set (`id`, `version`, later `passwordHash`).
   Binding a request straight onto an entity is how mass-assignment bugs happen.
2. **Coupling** — the API contract and the database schema change for different reasons and at
   different speeds. Renaming a column should not break every client.
3. **Serialization** — lazy JPA associations serialize badly (`LazyInitializationException`, accidental
   N+1, infinite recursion on bidirectional relations). A DTO is a flat, deliberate shape.

---

## D4 — Cross-cutting logging via AOP, not by hand in every method

**Decision.** One `LoggingAspect` with `@Around` advice over the service layer.

**Why.** Logging method entry, arguments, and elapsed time is a *cross-cutting concern*: it applies
identically to every service method and has nothing to do with any of them. Writing it inline means the
same six lines in fifty methods, and business logic buried in instrumentation. One aspect puts the
concern in one place, and the same aspect is copied into every microservice in Step 5 unchanged.

**Trade-off.** Spring AOP is proxy-based, so self-invocation (`this.otherMethod()`) is not advised, and
only Spring-managed beans are covered. Worth knowing — it is a standard interview follow-up.

---

## D6 — Flyway owns the schema; Hibernate is demoted to `validate`

**Decision.** From Step 2 on, `ddl-auto: validate` in every profile. All DDL lives in versioned Flyway
migrations under `db/migration`.

**Why.** The moment the database became persistent (a Docker named volume), `create-drop` started
deleting real data on every restart. The obvious alternative, `ddl-auto: update`, cannot actually evolve
a schema: it only adds — never renames, never drops, never changes a type safely — and what it does
depends on the database's current state, so environments drift apart over time. Most decisively, no
entity annotation can express a *data* migration ("split `name` into `first_name`/`last_name` and move
the existing rows"). A migration tool is the only thing that can.

The payoff is reproducibility: any environment — a teammate's laptop, CI, a fresh container in Step 10,
production — goes from empty to correct by replaying the same ordered files. Schema changes become
reviewable diffs in Git rather than tribal knowledge.

**Trade-off.** Every schema change now costs a hand-written SQL file, and an already-applied migration
cannot be edited (Flyway's checksum check refuses to start) — corrections must be a new version. That
rigidity is the point on a shared database, but it is friction on a solo toy project.

---

## D7 — Demo data is a repeatable migration, kept outside `db/migration`

**Decision.** `db/migration` holds schema only, versioned `V1`, `V2`, `V3`, … Sample books live in
`db/seed/R__dev_sample_books.sql` — a *repeatable* migration, loaded only because `application-dev.yml`
adds `classpath:db/seed` to `spring.flyway.locations`. The prod profile lists `classpath:db/migration`
alone.

**Why the separate location.** Migrations run in *every* environment by design, so anything placed in
them reaches production. Demo rows in a migration would ship five fake books to real users. Splitting by
location makes "dev-only" a property of configuration rather than of discipline.

**Why repeatable rather than versioned.** This was fixed after getting it wrong. The seed file was
originally `V900__dev_sample_books.sql`, chosen to sit clearly apart from the real schema history. That
pushed the database to version 900 — so the very next real migration, `V2`, was *lower* than the current
version and Flyway refused to start:

```
Detected resolved migration not applied to database: 2.
Validate failed: Migrations have failed validation
```

Flyway is right to refuse. Allowing an out-of-order migration means two environments can apply the same
set of files in different orders, which is how schemas silently diverge.

The real lesson is that seed data is not a step in the schema's evolution and should never consume a
version number. A repeatable migration (`R__` prefix, no version) runs after every versioned migration
and leaves the version sequence untouched.

**Consequence.** Repeatable migrations re-run whenever their checksum changes, so the file must be
idempotent — hence `ON CONFLICT (isbn) DO NOTHING`. That constraint is a preview of the same idempotency
requirement that returns for Kafka consumers (Step 7) and the S3 → Lambda pipeline (Step 9).

---

## D8 — Migration numbering: plain sequential integers

**Decision.** `V1`, `V2`, `V3`, … one number per schema change, never reused, never renumbered once
applied anywhere but a local throwaway database.

**Why.** The alternative used by most large teams is a timestamp (`V20260801143022__add_author.sql`),
which exists to solve exactly one problem: two developers on two branches both create `V5`, and the
merge is a conflict that neither Flyway nor Git can resolve safely. That problem does not exist on a
single-developer project, and sequential numbers have a real advantage here — they read as a history
("the schema is on its fourth change") and line up with the capstone's step-by-step commit requirement.

**Rules that follow from it.**

- **Never edit an applied migration.** Flyway stores a checksum; changing the file makes it refuse to
  start. Correct a mistake with a *new* migration.
- **The one exception** is a migration that has only ever run against a local database you are willing
  to destroy — then editing the file plus `docker compose down -v` is legitimate, and is exactly how the
  V900 mistake above was corrected.
- **Never reuse a number**, even for a migration that was deleted before being committed.
- On a team, if two branches collide on a number, renumber *your* migration upward before merging —
  safe precisely because it has not yet been applied to any shared environment.

---

## D9 — `LAZY` everywhere, fetched explicitly per query

**Decision.** Every association is `FetchType.LAZY`. Queries that need an association say so —
`@EntityGraph` for to-one, `LEFT JOIN FETCH` for to-many.

**Why not `EAGER`.** `EAGER` is the reflex answer to N+1 and it is the wrong one. It means *always*
join, including on every query that never touches the association, so it swaps N+1 for permanent
over-fetching. It is also a global decision made at the entity, where there is no information about
what any particular query needs. Fetching belongs to the query, not to the mapping.

**Measured on this project.** `GET /api/authors` went from 6 queries to 1; `GET /api/books?size=5` from
7 to 2. The `?naive=true` switch on the author endpoint keeps both paths reachable so the difference can
be demonstrated live rather than claimed — useful for the required demo video.

**The trap that comes with it.** A fetch join on a to-*many* association cannot be paginated in SQL:
`LIMIT` applies to joined rows, not to root entities. Hibernate does not fail — it loads the whole
result set and paginates in memory, with only a warning
(`HHH90003004: firstResult/maxResults specified with collection fetch; applying in memory`). Correct on
small data, an outage on large. Hence `findAllWithBooks()` returns a `List`. Where both are genuinely
needed the answers are `@BatchSize` or a two-query split (page the ids, then fetch collections for them).

---

## D10 — Search is an explicit `@Query`, not a derived method name

**Decision.** `BookRepository.searchByTitle` spells out
`LOWER(b.title) LIKE LOWER(CONCAT('%', :keyword, '%'))` instead of using the derived
`findByTitleContainingIgnoreCase`.

**Why.** The trigram index is built on `lower(title)`. Spring Data generates `UPPER(title) LIKE UPPER(?)`
for the `IgnoreCase` keyword, and an expression index is only used when the query's expression matches
it character for character — so the derived query fell back to a sequential scan while the index sat
unused: 21.9 ms against 0.175 ms on 100k rows, with nothing logged or warned. Writing the query out puts
the SQL and the index under one author's control, where the mismatch is visible in review.

**Alternative considered.** Building the index on `upper(title)` to match the generated SQL. It works,
but makes the index depend on a code-generation detail of the framework rather than on anything stated
in the codebase, and a Spring Data upgrade could quietly break it.

**General lesson worth keeping.** Always run `EXPLAIN` against the SQL the *application* emits, captured
from the logs — not against a hand-written approximation of it. The two differed here in exactly the one
respect that mattered.

---

## D11 — Optimistic locking on stock, with the database as the final word

**Decision.** `@Version` on `Book`, and 409 for every concurrency conflict —
`ObjectOptimisticLockingFailureException`, `InsufficientStockException`, and
`DataIntegrityViolationException` alike.

**Why optimistic.** Conflicts on any one book are rare. `@Version` costs nothing when nobody is
competing and only makes the loser retry. `SELECT ... FOR UPDATE` would serialise every purchase of a
book whether or not there was contention — the right tool when conflicts are the norm, the wrong one
here.

**Why `@Transactional` was not enough.** A transaction is atomicity, not isolation from a concurrent
read-modify-write. Under READ COMMITTED two transactions may both read `stock = 20` and both write `19`.
`@Version` turns the write into `UPDATE ... WHERE id = ? AND version = ?`, so the late committer matches
zero rows and is rolled back.

**Verified, not assumed.** 30 concurrent single-copy purchases against stock 30: 5 succeeded, 25 got
409, final stock exactly 25. The arithmetic is the assertion — without `@Version` successes would exceed
deductions.

**Two layers, deliberately.** The application checks (`existsByIsbn`, `stock >= quantity`) because it
can give a precise message. The database constrains (unique index, `CHECK (stock >= 0)`, version match)
because it cannot be raced or bypassed. The application explains; the database guarantees. Handling
`DataIntegrityViolationException` is what makes the second layer return 409 rather than 500 — closing
the check-then-act gap flagged in the Step 1 review.

---

## D5 — Cross-service references are plain IDs, not foreign keys

**Decision.** `order_item.book_id` and `orders.user_id` are plain `BIGINT` columns with no FK constraint.

**Why.** Database-per-Service. Each service owns its schema exclusively; there is no cross-database
foreign key to declare, and adding one would recreate the shared-database coupling that microservices
exist to avoid. When order-service needs a book's price or stock, it calls book-service's API.

**Consequence.** The database can no longer enforce referential integrity across that boundary — the
application must. Ordering a deleted book must be handled in code, not by a constraint violation. This
is the direct cause of the Step 5 saga / eventual-consistency work.
