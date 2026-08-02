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

## D5 — Cross-service references are plain IDs, not foreign keys

**Decision.** `order_item.book_id` and `orders.user_id` are plain `BIGINT` columns with no FK constraint.

**Why.** Database-per-Service. Each service owns its schema exclusively; there is no cross-database
foreign key to declare, and adding one would recreate the shared-database coupling that microservices
exist to avoid. When order-service needs a book's price or stock, it calls book-service's API.

**Consequence.** The database can no longer enforce referential integrity across that boundary — the
application must. Ordering a deleted book must be handled in code, not by a constraint violation. This
is the direct cause of the Step 5 saga / eventual-consistency work.
