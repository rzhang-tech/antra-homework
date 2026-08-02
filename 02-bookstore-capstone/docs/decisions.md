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

## D12 — No shared "common" library between services

**Decision.** `bookstore-platform` is a Maven aggregator that publishes no classes. Services duplicate
the small cross-cutting pieces — `ErrorResponse`, the JWT filter, `LoggingAspect` — rather than
importing them from a shared jar.

**Why.** A shared domain or utility library is how a set of microservices quietly becomes a distributed
monolith. One team's change to a shared class forces every other service to rebuild, retest and redeploy
in lockstep — which is exactly the coupling the split was meant to remove. The services keep separate
databases precisely so they can evolve separately; sharing their code puts the coupling back at a level
that is harder to see.

**The concrete case that settled it.** book-service's copied `JwtUtil` referenced user-service's `Role`
enum. Sharing that enum would mean user-service could not add a role without breaking the catalog's
security filter at runtime. Instead `roleOf` returns a `String`, and an unrecognised role matches no
authorization rule and becomes a 403. Each service now decides for itself what it does with a value it
does not recognise, which is what independent deployability actually requires.

**Trade-off, honestly.** Some genuine duplication — roughly eight small files per service. That is the
price, and it is cheap compared with a lockstep release train. Where duplication would become
expensive (an OpenAPI client, say) the answer is a *generated* contract rather than a hand-shared
class.

---

## D13 — Retry reads, never retry the stock write

**Decision.** `CatalogGateway.findById` is retried three times with backoff. `CatalogGateway.purchase`
is never retried. Both are behind the same circuit breaker.

**Why.** A GET is idempotent, so repeating it after a dropped packet costs nothing. Decrementing stock
is not, and the case that makes retry dangerous is the one that looks like failure: book-service commits
the decrement and the response is lost on the way back. The caller sees a timeout and cannot distinguish
it from "nothing happened".

Retrying takes a second copy off the shelf. Not retrying leaves an order that failed with stock already
gone. **Neither is correct** — this layer cannot tell the two cases apart. Losing stock is recoverable
by reconciliation; overselling a customer is not, so the error to prefer is the one that under-sells.

**The actual fix, deferred to 5d.** Make the operation idempotent — a request id book-service records,
so a repeat is recognised rather than reapplied. Then retry becomes safe and the dilemma disappears.
That this decision exists at all is a symptom, not a design.

---

## D14 — A fallback that fails fast, not one that invents data

**Decision.** The circuit-breaker fallback raises `CatalogUnavailableException` (a 503) immediately. It
returns no cached price, no default stock, no placeholder book.

**Why.** The usual example returns a substitute value so the caller "degrades gracefully". For a price
and a stock level that would be indefensible: charging an invented price, or selling stock that may not
exist, is far worse than an honest error. Graceful degradation is only graceful when the degraded answer
is still true.

**Where the value actually comes from.** Not the substitute — the *speed*. Measured on the running
platform, with book-service stopped, ordering went from **761 ms to 85 ms** once the circuit opened, and
no request left the process. A catalog outage stops consuming order-service's threads, which is what
prevents one service's failure from becoming everybody's.

**A caveat worth remembering.** `ignore-exceptions` keeps business errors out of the failure rate, but
does **not** stop the fallback running — a fallback catches everything the guarded method throws. Without
an explicit rethrow, "no such book" reached the customer as 503: the catalog answered correctly and the
service reported an outage. Excluding them from the *rate* matters just as much in the other direction:
a breaker that counts 404s as failures opens under entirely healthy traffic.

---

## D15 — Sagas point whichever way is cheaper to reverse

**Decision.** Placing an order unwinds on failure; paying for one rolls forward. Same mechanism —
durable state, a scheduled recovery job, idempotent steps — in opposite directions.

**Why.** Reversing a stock reservation is a release call: cheap, invisible to the customer, leaving no
trace. Reversing a charge is a refund: slow, visible, and in a real system it costs money. So when the
last step of an order fails, undo; when the last step of a payment fails, keep trying to finish.

**The consequence in code.** `pay` returns *success* when the charge worked but telling order-service
did not. Reporting an error would tell a customer their payment failed when it succeeded, and invite
them to pay twice. `PaymentRecoveryJob` therefore has no attempt limit — there is no acceptable resting
state for "customer charged, order unaware", and marking it resolved after N attempts would amount to
quietly deciding to keep the money.

**Which direction to point is a business question, not a technical one.** The pattern supports both
equally; only the cost of reversing each step decides.

---

## D16 — Background work needs its own identity

**Decision.** Outgoing calls forward the caller's token when there is a request in flight, and use a
short-lived service token minted by `ServiceTokenProvider` when there is not.

**Why.** `PaymentRecoveryJob` runs on a timer, long after the customer has gone. The first version had
nothing to forward and called order-service anonymously — 401 on every attempt, a recovery mechanism
that could never recover anything, failing quietly. **Identity propagation covers synchronous work
only**; a scheduled job, a Kafka consumer (Step 7), or any retry after the caller has left needs an
identity of its own.

**The tension with D12's neighbour.** Step 5a deleted `JwtUtil.generate` from book-service, arguing that
two services able to mint credentials is two places to audit. That argument still holds. The difference
is that book-service only ever acts for a caller who is present, while payment-service must act
autonomously to complete a saga — and a service that acts on its own behalf cannot borrow someone
else's identity to do it.

**Bounded by:** minted per call, two-minute lifetime, never stored, used only when no request is in
flight, and identifiable as `service:payment-service` in an audit log.

**Not bounded enough:** the role is `ADMIN`, which is more authority than one endpoint needs. A distinct
`SERVICE` role scoped to that route, or mTLS against an internal-only endpoint with no bearer token at
all, is the real answer — both need the gateway from Step 8 to separate inside from outside.

---

## D17 — A service refuses to start rather than start on defaults

**Decision.** `spring.config.import` is not `optional:`, `spring.cloud.config.fail-fast` is `true`, and
retry covers roughly 27 seconds of a config server being slow to come up.

**Why.** The alternative is that an unreachable config server produces a running service configured from
whatever happens to be bundled in its jar. That service comes up holding the wrong signing key, rejects
every token on the platform, and reports itself healthy the whole time. **A process that will not start
is a visible, obviously-attributable failure; a process running on stale configuration is an invisible
one**, and someone spends the outage looking at the wrong service.

**The retry exists because fail-fast is not fail-instantly.** On a cold start every service comes up at
once and the config server may be seconds behind — a race Step 10's orchestration makes routine. Six
attempts backing off from one second turns that race into a non-event. Measured with the server
unreachable: 4.5 s with retry off, 31.2 s with it on.

**Where this is not enough.** The startup dependency is real, and it is new. The config server is now a
single point of failure for the whole platform's ability to *start* — not to run, since a running
service keeps its configuration — and Step 10 has to make it highly available or accept that a config
server outage plus a pod restart equals an outage.

---

## D18 — Two different mechanisms for secrets, because they solve different problems

**Decision.** Dev keeps the signing key in the config repo as a `{cipher}` value the config server
decrypts with a key from `ENCRYPT_KEY`. Production keeps it out of the config repo entirely, as a
`${JWT_SECRET}` placeholder the *client* resolves against its own environment.

**Why not just encryption.** The config server decrypts before serving, which is what makes it
convenient — no client needs the key or an extra dependency. It is also the limit of what it protects.
Anything that can reach the config server can read the plaintext, and `/decrypt` will convert any
ciphertext back on request. Encryption keeps credentials out of a **Git history**, which matters more
than it sounds: a repository keeps the value forever, including after it is rotated, and every developer
with read access can browse it.

**Why not just placeholders.** They are strictly stronger — the secret never enters the repository, the
wire, or the server's logs; the config server is told the *name* of the secret, not the secret. But they
push the problem to whatever populates the environment, which on a laptop is nothing, and a dev
environment where every developer must first be handed a set of secrets out of band is a dev environment
nobody can start.

**The property that made this decision easy:** the config server does not resolve `${...}` — verified,
not assumed. It serves the literal `${JWT_SECRET}`, so the same file works for a Kubernetes Secret in
Step 10 with no change at all.

**Where this repository does not live up to its own rule.** `ENCRYPT_KEY` is documented in
`bookstore-platform/README.md` and `test-platform.http`, in the same repository as the ciphertext —
so the encryption protects nothing *here*. Stated rather than quietly true: a capstone has to be
runnable by whoever clones it, and the alternative is a project that cannot start until someone is
handed a key out of band. The dev key protects a dev secret, so the exception costs nothing real; it
would be indefensible for any value that mattered, and it is why production uses placeholders instead
of encryption rather than as well as it.

**Not solved.** Rotating the key is still not a configuration change. Refresh reaches one service at a
time, so the moment user-service signs with a new key every token in flight and every service still
holding the old key disagrees with it. Real rotation needs a verifier that accepts both keys for longer
than a token lives — a key *set*, which is code.

---

## D19 — Immutability given up in exactly one class, for refreshability

**Decision.** `JwtProperties` in user-service is a mutable class with setters. Everywhere else,
`@ConfigurationProperties` types stay records.

**Why.** `POST /actuator/refresh` rebinds an *existing* bean. A record is bound through its constructor,
so there is nothing to rebind — and `@RefreshScope` on the consumer does not help either, because the
rebuilt consumer is handed the same stale record. Measured at each stage, changing the configured token
expiry from 60 minutes to 5 and logging in again:

```
no @RefreshScope, JwtProperties a record    env says 5, tokens 60
@RefreshScope,    JwtProperties a record    env says 5, tokens 60
@RefreshScope,    JwtProperties a class     env says 5, tokens  5
```

**The trade, stated plainly:** immutability is the better default and was given up deliberately, in one
class, for the ability to change a value without a restart. The setters exist for the binder; nothing in
the codebase calls them, and anything that did would be changing configuration behind `JwtUtil`'s back.

**The general rule this is an instance of.** `/actuator/refresh` always updates the Environment, and
updating the Environment changes nothing by itself — something has to be able to read the value again.
Two beans in a chain, and the refresh lands only when *both* can be rebuilt. The same reasoning explains
why `spring.cloud.openfeign.client.refresh-enabled` refreshes Feign's timeouts and not its URL: the URL
is resolved into the target when the client is built, and nothing rebuilds it.
---

## D20 — Events for everyone else; calls for what has to happen

**Decision.** payment-service publishes `PaymentCompleted` *and* keeps calling order-service
synchronously to mark the order paid, with 5e's never-giving-up recovery job intact. Placing an order
publishes `OrderPlaced` and calls nobody.

**Why.** **An event tells people something happened; a call makes something happen.** order-service must
know the money arrived before it hands over books, and "eventually, once some consumer catches up" is
not a guarantee to put between a customer and their order. Replacing the call with an event would have
looked like better architecture and quietly weakened a guarantee the business depends on.

Confirmations and analytics are the opposite case. Nobody is waiting, nothing breaks if they are a
minute late, and — the part that matters — **the producer must not have to know they exist**. Adding
analytics-service required no change to order-service at all.

**The test to apply:** if the caller needs the effect to have happened before it can proceed, it is a
call. If the caller is merely announcing a fact, it is an event. Publishing an event and also waiting
for its consumer is the worst of both.

**Only successes are published.** A declined payment is information a fraud service would want and a
receipt service must never act on, and a topic is read by consumers who cannot be enumerated. Facts that
are safe for all of them, or a separate topic.

---

## D21 — Consumers deduplicate on a natural key, in the work, not in the listener

**Decision.** Every consumer checks "have I already done this for order N?" before acting, keyed by
order id, inside the class that does the work.

**Why deduplicate at all.** Kafka delivers at least once, and that is the better of the two options: a
consumer commits its offset after processing, so a crash in between redelivers, while committing first
would trade duplicates for silent loss. A rebalance, a slow poll, a restarted pod and a producer retry
all produce the same redelivery.

**The broker cannot help.** Producer idempotence removes duplicates *one producer session* creates;
transactions give exactly-once between topics. Neither covers a consumer adding a number to a total
twice, because that side effect lives outside Kafka. At-least-once is a statement about your code.

**Why a natural key.** Same argument as 5e's `order_id UNIQUE`: a caller cannot forget it and cannot
regenerate it. A per-message UUID deduplicates only identical retransmissions — republish the same order
from a restarted producer with a fresh UUID and it counts twice again.

**Why in the work.** "Have I already confirmed this order?" would need answering identically if the
event arrived over HTTP or was replayed from a file during a migration. A listener that deduplicated
would leave the method unsafe for every other caller.

**Why one guard per unit of work, not per entity.** `ReceiptSender` has its own store rather than
sharing `ConfirmationSender`'s. A shared "have I seen order 17?" would mean confirming an order
suppressed its receipt.

**The limit, stated rather than hidden.** A guard must be **at least as durable as the effect it
guards**. analytics-service's protects an in-memory tally, so both are lost together and cannot
disagree. notification-service's protects an email that has already left — restart it mid-redelivery and
the customer gets a second confirmation. Both are bounded at 10,000 ids, so an older redelivery would
slip through. Correct for this platform's seconds-long redelivery window; a database table or a Redis
key with a TTL is the answer where it is not.

---

## D22 — A dead letter topic per consumer group, and a monitor over all of them

**Decision.** Failed records go to `<topic>.<consumer-group>.DLT` after a small retry budget — or
immediately, if the failure is deterministic — and analytics-service counts what is sitting in every
DLT on the platform.

**Why a DLT.** Ordering is per partition and the container honours it, so a record that keeps throwing
blocks every later record on its partition. One malformed message stops a third of a service's work,
and the only symptom is a consumer that has gone quiet. Measured: a poison message and then a good one
on the same partition were 572 ms apart, because the bad one was routed away instead of retried forever.

**Why per group and not per topic.** Spring's default `<topic>.DLT` pours every consumer's failures into
one place. Two services read `bookstore.order.placed`, so "how many notifications are stuck?" would
require inspecting each record, and a replay tool would re-deliver one service's failures to another.
The group name is what makes a failure attributable.

**Why deterministic failures skip the retries.** Malformed JSON fails the same way on the fourth attempt
as on the first; the retries are pure delay while the partition waits. Transient failures get three
attempts over about seven seconds. The budget is deliberately small — the DLT is the last line of
defence, and a long budget turns "one bad message" into "this partition is minutes behind".

**Why the monitor is the important half.** The DLT removes the *symptom* along with the failure. Before
it, a poison message made a consumer visibly stop; after it, the consumer looks healthy while orders
quietly go unconfirmed. **A dead letter topic without a monitor is worse than no dead letter topic**,
because it converts a loud failure into a silent one. The threshold is one, the healthy state is
silence, and depth (end offset minus start offset) is the number that matters — nothing consumes these
topics, a human does.

**Not good enough for production:** a monitor inside a service can fail with it, and this one is a
scheduled method rather than an alert with an owner. Step 11 replaces it with a real one.
---

## D23 — The gateway authenticates; the services authorize; both verify

**Decision.** The edge checks that a token exists, is genuine and is unexpired, and refuses anything
else before routing. It knows no authorization rules. The token is forwarded untouched and every service
verifies the signature exactly as it did before the gateway existed.

**Why authenticate at the edge.** A request with no token, or a forged one, is refused in microseconds
without costing any service a connection, a thread or a database session. Measured: two rejected
requests produced **zero** log lines in order-service; one accepted request produced 28.

**Why not authorize there.** A rule stated in two places drifts, and the copy on the edge is the one
nobody updates when the service changes. The edge cannot answer "may this customer read this order?"
without knowing whose order it is, which means either duplicating the domain or asking the service —
and asking the service is what the service was going to do anyway.

**Why the services still verify.** Because the alternative — strip the token, forward
`X-Auth-User-Id`, trust it — puts the platform's entire authorization model on network topology that
nothing enforces. Anything able to reach a service directly could claim to be anybody. **A network
boundary is not a security boundary until something makes it one**, and this platform has no mTLS and no
service mesh. Demonstrated: forged identity headers sent straight at order-service and at book-service
both get 401, because a signature is what those services check.

**The corollary that carries the risk:** headers a proxy sets must be headers a proxy also clears.
Inbound `X-Auth-*` is stripped unconditionally — on public routes, and on requests about to be refused —
because the value of that guarantee comes entirely from having no exceptions to reason about.

**When the trusted-header design becomes correct:** with mTLS between the gateway and the services, or a
service mesh enforcing identity, or a NetworkPolicy that genuinely makes the gateway the only reachable
caller. It is a real design, and it needs a real boundary underneath it.

---

## D24 — CORS is a browser mechanism, configured once, and it is not access control

**Decision.** One CORS configuration at the gateway, with named origins rather than `*`,
`allow-credentials: false`, and `Authorization` explicitly allowed. No service configures CORS.

**Why here.** "Which web origins may a browser let talk to this API" is a question about the front door.
Six services would have needed six identical blocks, and a browser calling two of them would have been
at the mercy of whichever was edited last.

**The interaction that must be right.** A browser sends `OPTIONS` with **no `Authorization` header at
all**. If the edge auth filter answered that preflight with 401, the real request would never be sent
and the developer would see a CORS error in the console with nothing mentioning a token.
`EdgeAuthenticationFilter` therefore runs at `HIGHEST_PRECEDENCE + 100`, leaving room for the CORS
filter ahead of it. That is a filter *ordering* property and cannot be verified by reading YAML, so
`CorsTest` asserts it with a request.

**The misreading worth stating plainly.** CORS protects the *user's browser*, not the API. The check
keys on the `Origin` header, and curl, every server-side client and every attacker simply omit it:

```
valid token, no Origin header       200      <- unaffected by CORS entirely
valid token, disallowed Origin      403
no token,   allowed Origin          401
```

Removing an origin stops a page on that origin reading responses in a browser. It stops nothing else.
Treating an allow-list as a security boundary is one of the most common mistakes in web development.

---

## D25 — Actuator on a separate port, because a filter only guards what it sees

**Decision.** The gateway serves its API on 8080 and its actuator on 9090, and only 8080 is public.

**Why this exists.** `curl localhost:8080/actuator/env` returned 200, with the platform's configuration
in it, on the component facing the public internet. Every *service* had ADMIN-only actuator from Step
6c, enforced by its Spring Security filter chain; the gateway has no filter chain.
`EdgeAuthenticationFilter` is a `GlobalFilter`, and a `GlobalFilter` runs only for requests the route
table matched. `/actuator` is served by a different handler mapping, so the filter never saw it.

**Why a separate port rather than a fourth copy of the ADMIN rule.** There is no rule to get wrong and
no filter ordering to reason about, and in Step 10 only 8080 is named in the Service — so the management
port is unreachable from outside the pod *by construction* rather than by policy. Diagnostics stay
available where diagnostics are done: `kubectl port-forward`, or localhost.

**The general lesson, which is bigger than the fix.** A guard attached to one mechanism does not protect
what arrives through another. "Everything goes through the filter" was true of every request anyone was
thinking about, and false for the one that mattered. When a guard is a filter, the question to ask is
not "is it correct?" but "what reaches this process without passing through it?"
---

## D5 — Cross-service references are plain IDs, not foreign keys

**Decision.** `order_item.book_id` and `orders.user_id` are plain `BIGINT` columns with no FK constraint.

**Why.** Database-per-Service. Each service owns its schema exclusively; there is no cross-database
foreign key to declare, and adding one would recreate the shared-database coupling that microservices
exist to avoid. When order-service needs a book's price or stock, it calls book-service's API.

**Consequence.** The database can no longer enforce referential integrity across that boundary — the
application must. Ordering a deleted book must be handled in code, not by a constraint violation. This
is the direct cause of the Step 5 saga / eventual-consistency work.
