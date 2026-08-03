# Reflection — What I Would Improve

Submission deliverable #3. Filled in step by step as gaps appear, so the final write-up is grounded in
things actually hit during the build rather than assembled at the end.

Every item below was met while building, not imagined afterwards. Where a gap was later closed it is
struck through with the step that closed it, because a list of problems that were all solved is not a
reflection — the interesting entries are the ones still open, and why they were left open.

---

## The five I would fix first

Ranked by **how silently they fail**, not by how hard they are. A defect that announces itself gets
fixed by whoever trips over it; a defect that leaves no trace gets discovered by a customer.

### 1. The dual-write hole between the order and its event (Step 7)

`place()` commits the order, then publishes `OrderPlaced`. If the broker is unreachable in between —
or the process dies — the order exists and nobody was ever told. No error, no retry, no row, no log
line saying something was lost. A database write and a Kafka send cannot be one atomic act.

**The fix:** a transactional outbox. Write the event into order-service's own database *in the same
transaction as the order*, and let a poller publish from that table and mark rows sent. The dual write
becomes a single local transaction plus an at-least-once relay, and consumers are already idempotent
(D21), so the duplicates the relay can produce cost nothing.

**Why it is first:** it is the only outstanding defect that can produce a customer-visible
inconsistency with no evidence anywhere that it happened.

### 2. The signing key is symmetric, and five processes hold it (Steps 3, 8)

user-service signs with HS512 and every other service verifies with the same key — which means every
service *could* sign. Step 5a deleted `JwtUtil.generate` from book-service and the gateway carries no
issuing code at all, but that is a discipline, not a boundary: the bytes are there. The gateway makes
this sharper by putting one copy on the public edge.

**The fix:** asymmetric keys. user-service signs with a private key; everything else verifies with a
public one. A compromised verifier — or a compromised gateway — can then read tokens and forge none.
Rotation also stops being the all-or-nothing switch described in `JwtUtil`: publish the new public key
everywhere first, then start signing with the new private key.

**Why it is second:** the blast radius. Every other item on this list damages data; this one damages
identity, and identity is what every authorization rule in the platform rests on.

### 3. An idempotency guard less durable than the effect it guards (Step 7c)

notification-service dedupes on order id in a bounded in-memory set. The email it protects has left the
building. Restart the service while a redelivery is outstanding and the customer gets a second
confirmation — the guard forgot, the inbox did not.

analytics-service has the same guard and it is *correct* there, because the tally it protects is also in
memory: both are lost together and cannot disagree. The rule the pair illustrates is the fix: **a guard
must be at least as durable as the effect it guards.**

**The fix:** persist notification-service's guard — a table with the order id as primary key, or Redis
with a TTL longer than the worst redelivery window. Both stores are also bounded at 10,000 ids, so an
older redelivery slips through; a TTL is the honest bound rather than a count.

### 4. Event contracts have no schema registry (Step 7)

Each service holds a hand-written copy of `OrderPlaced` and `PaymentCompleted` (D12 — deliberately, to
avoid a shared jar). Nothing fails at build time when a producer renames a field. A consumer binds
`null` at runtime, quietly, and a confirmation email goes out with a blank total.

**The fix:** Avro or JSON Schema with a registry, so the producer's build fails on an incompatible
change. The project's smaller version already exists — `OrderPlacedListenerTest` publishes raw
producer-shaped JSON and asserts it binds — but that test lives in the *consumer*, so it catches the
break one repository too late.

### 5. Scheduled jobs run on every replica (Step 5d)

Three jobs are `@Scheduled`: `OrderRecoveryJob`, `PaymentRecoveryJob` and `DeadLetterMonitor`. With one
instance each that is fine. Step 10 sets `replicas: 3`, and then three copies scan for stranded orders
simultaneously.

The first two are *harmless* by luck — releasing a reservation twice is a no-op and marking an order
FAILED twice is idempotent. `DeadLetterMonitor` is the one that shows why luck is not a design: three
replicas produce three identical `DEAD LETTER:` warnings for one stuck message, and an operator who
learns that the count in the log means nothing is an operator who has stopped reading it. **The
prediction that the next scheduled job would not be harmless was already true when it was written.**

**The fix:** ShedLock (a shared advisory lock in PostgreSQL) or a single leader elected per job. Cheap,
and it needs doing *before* Step 10 rather than after.

---

## Step 1 — monolith skeleton

**Known gaps, deliberately deferred**

- ~~**The ISBN uniqueness check has a race condition.**~~ Fixed in Step 2e. `GlobalExceptionHandler` now
  maps `DataIntegrityViolationException` to 409, closing the check-then-act gap. Verified with 20
  concurrent creates of the same ISBN: 1×201, 19×409, exactly one row written.

- **Keyword search will not scale.** `findByTitleContainingIgnoreCase` produces `LIKE '%keyword%'`. The
  leading wildcard makes a B-tree index on `title` unusable, so this is a full table scan on any real
  catalog. Step 2 revisits it with `EXPLAIN ANALYZE`; the real fix is a PostgreSQL trigram (`pg_trgm`)
  or full-text index, or a search engine if the catalog grows.
- **No pagination cap.** `?size=100000` is accepted. A `max-page-size` limit belongs here before the API
  is public.
- ~~**`data.sql` + `ddl-auto: create-drop` is a dev crutch.**~~ Fixed in Step 2a: schema moved to Flyway
  migrations, seed data to a repeatable migration loaded only under the dev profile.
- **Everything is public.** Anyone can `DELETE /api/books/{id}`. Step 3.
- **Only a context-loads test.** Real coverage is Step 4 — deliberately before the Step 5 refactor, since
  tests you write after a refactor only prove what the refactor produced.

**What I would do differently if starting over**

- Model the `Author` relation from the beginning rather than adding it in Step 2. Splitting it across
  steps made the first schema slightly artificial.
- The `PageResponseDto` mapper is generic but every caller passes the same mapping function; a small
  amount of that generality is unused.

---

## Step 2 — the data layer

Mostly a step that *closed* gaps rather than opening them. What it left:

- ~~**Keyword search is a full table scan.**~~ Fixed in 2d with a GIN trigram index, measured with
  `EXPLAIN ANALYZE` against generated data rather than against six demo rows.
- **The trigram index costs writes.** Measured at **4.4× slower inserts** on the benchmark data. That is
  the right trade for a read-heavy catalogue and it is written down in the commit, but nothing monitors
  it — if the catalogue ever becomes write-heavy, no alert exists to notice the index has stopped
  paying for itself. *An index is a decision, not a reflex,* and a decision deserves a metric.
- **The bidirectional `Author` <-> `Book` relation is a liability nobody has tripped over yet.**
  `Author.books` is `LAZY` and every read path that needs it uses an explicit `LEFT JOIN FETCH`, which
  is why 2c's numbers hold. Nothing enforces that: a new query that touches `author.getBooks()` outside
  a fetch join reintroduces the N+1 silently, and no test would fail. A Hibernate statistics assertion
  in the slice tests — "this endpoint issues exactly N queries" — is the guard, and 2c proved the
  measurement works without turning it into one.
- **The `?naive=true` switch is a teaching artifact**, not a feature. It exists so the N+1 can be
  reproduced on demand. It is documented as such, but a query parameter that deliberately degrades
  performance would not survive a real code review, and it would be the first thing removed.
- **Still no pagination cap** (carried from Step 1, still open).

---

## Step 3 — authentication and authorization

- **Tokens cannot be revoked.** A JWT is valid until it expires, even if the user is deleted or demoted.
  That is the price of statelessness, and the reason expiry is sixty minutes rather than weeks. A real
  system adds refresh tokens plus a revocation list — which reintroduces the shared state JWTs were
  chosen to avoid, and is a trade worth making deliberately rather than discovering during an incident.
- **No account lockout and no rate limit on login.** Password comparison is deliberately constant-ish
  and failed logins are byte-identical whether or not the username exists, so nothing leaks *which*
  accounts are real — but nothing stops a million attempts either. The gateway (Step 8) is the right
  place, and it still does not do it.
- **Roles are a flat enum with no hierarchy.** Every rule names both roles explicitly
  (`hasAnyRole("USER", "ADMIN")`) because Spring Security roles are not hierarchical. It works and it is
  verbose; a real system with more than two roles needs a `RoleHierarchy` before the rules become
  unreadable.
- **Symmetric signing key** — see item 2 above.

---

## Step 4 — testing

- **Coverage is uneven by design and unmeasured in fact.** The service layer, the web layer and the
  repository layer are all covered, but nothing reports a number, so "is this well tested?" is answered
  by reading rather than by a tool. JaCoCo in the build — with a threshold that fails, not a badge that
  informs — is a half-hour of work that was not done.
- ~~**Nothing tests the platform end to end.**~~ Still open, and it got worse in Step 5: see below.
- **No mutation testing.** Every test here asserts something true; none of them prove they would fail if
  the code were wrong. PIT would answer that, and for the saga logic in Step 5 it would answer something
  worth knowing.

---

## Step 5 — the split into microservices

**What got worse, and was accepted:**

- **Four processes and four databases to run a bookstore.** A real cost, and the direct reason Steps 10
  and 11 exist.
- ~~**"What is public across the platform?" is no longer answerable from one file.**~~ Partly restored by
  Step 8's gateway — but only partly, because the gateway holds a *coarse* public-route list and each
  service still owns its authorization rules. The honest answer today is "two files, and the second one
  is the authority".
- ~~**The signing key is shared configuration, as the same literal in several files.**~~ Fixed in Step 6:
  one copy, in the config repo.
- **Nothing tests the platform end to end automatically.** Still true, and now the oldest open gap in
  this document. Per-service tests are honest about their scope; `test-platform.http` is a *manual* run.
  The fix is a Testcontainers-based suite that starts the whole platform and drives one purchase through
  it — which becomes straightforward the moment Step 10 produces images, and that is the argument for
  doing it there rather than now.

**Still outstanding from the saga work:**

- **Compensation is at-least-once, not exactly-once in the face of everything.** If book-service is down
  longer than the recovery job's patience, stock stays held until it returns. The job keeps retrying,
  which is right, but "eventually" is doing real work in that sentence, and nothing alerts on how long
  eventually has been.
- **The recovery jobs run on every replica** — item 5 above.

---

## Step 6 — central configuration

- **A fifth process that everything depends on to start.** Fail-fast plus retry make its absence loud
  rather than harmless, which is the correct trade — but it is a new single point of failure for
  *startup*, and Step 10 has to make it highly available or accept that a config-server outage plus a
  pod restart equals an outage.
- **"Where does this value come from?" now has four possible answers per service**, ordered by a rule
  that is not the obvious one (profile beats specificity). `/actuator/env` is the only reliable way to
  answer it, which is why it is exposed — and exposing it is itself a small cost.
- **Configuration and code can drift apart.** A resilience threshold naming
  `com.example.order.exception.ResourceNotFoundException` sits in YAML that no compiler checks. Rename
  the class and the config server keeps serving the old name perfectly happily. A startup check that
  every `ignore-exceptions` entry resolves to a real class would cost twenty lines.
- **Encryption in this repository protects nothing**, and the docs now say so. `ENCRYPT_KEY` is
  documented two files away from the ciphertext it decrypts, because a capstone has to be runnable by
  whoever clones it. It is a stated exception rather than an oversight, it costs nothing real (a dev key
  protecting a dev secret), and it is exactly why production uses `${JWT_SECRET}` placeholders *instead
  of* encryption rather than as well as it.
- **Refresh reaches beans, not everything.** `server.port`, the datasource and the signing key still
  need a restart. That is fine and stated; what is *not* fine is that nothing prevents someone assuming
  otherwise — `/actuator/refresh` returns a list of changed keys whether or not anything acted on them.

---

## Step 7 — asynchronous messaging

- **The dual-write hole** — item 1 above, and the largest thing this project leaves undone.
- **"What happens when an order is placed?" is no longer answerable by reading order-service.** The
  synchronous version was traceable in a debugger. This one requires knowing which topics exist and who
  subscribes, and the answer changes when somebody deploys a new consumer. That is the cost of the
  decoupling, not a defect — but it means the architecture diagram is now load-bearing documentation
  rather than a nice-to-have.
- **No schema registry** — item 4 above.
- **Two more processes that nothing monitors except by reading logs**, both keeping their state in
  memory. analytics-service's tally is lost on every restart, which is acceptable only because it is a
  demonstration; a real one writes to a warehouse.
- **The DLQ monitor lives inside a service.** A monitor inside the thing that is failing can fail with
  it. The honest version is a Prometheus alert with an owner, and it is on the Step 11 list.
- **Consumer lag is not alerted on.** DLQ depth is. A consumer that is merely *behind* — the far more
  common failure — is invisible.

---

## Step 8 — the API gateway

- **A single point of failure with a queue behind it.** Every request now depends on one more process.
  Statelessness makes it horizontally scalable, which is the answer; Step 10 has to actually run more
  than one.
- **The public-route list is duplicated** between the gateway and the services. In the *safe* direction
  — the service is the authority, so an over-permissive edge only wastes a hop — but nothing checks that
  the two still agree, and a contract test could.
- **The gateway holds the signing key** — item 2 above, and the gateway is what makes it urgent.
- **Nothing rate-limits.** The edge is the only place that could, and it does not. Spring Cloud
  Gateway's `RequestRateLimiter` with Redis is the standard answer, and login is the endpoint that
  needs it most (see Step 3).
- **The bypass demonstrated in Step 8b is real in development.** Ports 8081-8086 are reachable. The
  services verify tokens so nothing is *gained* by bypassing, but a NetworkPolicy in Step 10 should make
  the demonstration impossible rather than merely pointless.

---

## Step 9 — S3, Lambda and DynamoDB

Updated in the same commit as the step, which is the point of the last entry below.

- **The platform now has state this repository cannot recreate.** Dropping `UserBrowsingHistory` loses
  every view with no migration to replay. Flyway's guarantee for PostgreSQL has no equivalent here: the
  provisioning scripts describe *structure* and never contents. A real system exports to S3 on a
  schedule, or accepts that history is disposable and says so out loud.
- **Every AWS interaction is asserted against a mock.** A test needing an AWS account is a test that
  does not run in CI, so the request shapes are pinned and the round trip is demonstrated by hand. What
  that leaves unchecked is exactly what broke most often: whether the table exists with those keys, and
  whether the environment's credentials can reach it. DynamoDB Local via Testcontainers, plus LocalStack
  for S3, would close most of the gap and is a day's work.
- **The Lambda's contract with book-service is a string format with two implementations.**
  `CoverStorageService.keyFor` builds `covers/{bookId}`; `CoverProcessor.bookIdFrom` takes it apart.
  There are tests on both sides, and nothing makes them fail *together* — if they drift the pipeline
  stops silently, with the object landing, the event firing, and no metadata or email appearing.
- **Cost became a property of correctness, and nothing watches it.** A hot partition, a missing TTL or a
  forgotten lifecycle rule does not fail — it bills. There is a manually-created budget alarm on the
  account and nothing in the project.
- **The DLQ alarm lags by five to ten minutes**, because SQS publishes its depth metric on a
  five-minute cadence. Adequate for covers, and it would not be for anything a customer waits on.
- **A test passed while testing something else, and I nearly shipped the note instead of the fix.**
  The first DLQ demonstration failed at event *deserialization*, so the function's own code never ran —
  the plumbing was proven and the thing the test existed for was not. The discrepancy was recorded
  accurately in the README and then left there, which felt like rigour and was not: **an honest label
  on a gap is not the same as closing it**, and the honesty is what made the shortcut feel principled
  enough to stop thinking about. Redoing it properly took three minutes and turned up a second finding
  — 403 versus 404 on `s3:ListBucket` — that the first version could never have surfaced. The rule
  worth keeping: when a measurement does not measure what it was set up to measure, that is an open
  action, not a footnote.
- **Infrastructure is shell scripts rather than CloudFormation or Terraform** (D28). A stated shortcut,
  not a recommendation: nine resources did not justify also teaching a provisioning DSL, and the scripts
  are shaped so the translation is mechanical.

---

## Step 10 — containers, compose and Kubernetes

- **Two services cannot be scaled and nothing enforces it.** analytics-service and
  notification-service keep their state — a running tally and an idempotency guard — in a JVM heap, so
  a second replica produces wrong numbers and duplicate emails respectively, silently. Measured: two
  analytics pods reported 2 orders / 99.98 and 4 orders / 199.96 for the same seven orders, and neither
  is right. A comment in `60-autoscaling.yaml` is the only thing stopping somebody running
  `kubectl scale`. **The fix is that neither piece of state belongs in a heap** — a table with a
  retention policy, or Redis with a TTL longer than the worst redelivery window, which 7c already named.
- **Load balancing works because of a timeout.** A Service balances *connections*, not requests, so the
  gateway's pooled connection sent 20 of 20 requests to one of two order-service pods. Bounding
  connection lifetime to 10s made it 19/21. That is a workaround with a guessed number in it; the real
  answer is L7 load balancing, meaning a service mesh, and that is a bigger addition than this platform
  can carry.
- **The config repo is not validated anywhere.** A duplicate `httpclient` key made the config server
  return 500 for `/api-gateway/dev` — and the *running* gateway carried on serving perfectly, because
  it read its configuration at startup and never again. Only the pods the HPA had just created failed,
  which means a bad configuration change surfaced **at peak load, in new pods, with the old ones
  looking healthy**. `ConfigServerContractTest` asserts what the files say and never that they parse. A
  test that simply loads every file in `config-repo/` is twenty lines and belongs in Step 11.
- **Images are built from untested code** (D30). `-DskipTests` in every Dockerfile, because the suite
  needs Testcontainers and that would mean docker-in-docker. "It built" currently means "it compiled".
  Step 11's pipeline is where a failing test stops an image existing.
- **`:latest` plus `imagePullPolicy: Never` is not a version.** It means "whatever was last loaded into
  this node", which cannot be rolled back to. Commit-SHA tags in a registry are Step 11.
- **The databases run in the cluster with one replica, no backups and no failover.** D26 already argues
  for RDS; the honest reason they are in-cluster is that a capstone should start with one command for
  whoever clones it. Stated rather than implied.
- **A Kubernetes Secret is base64, not encryption**, and it holds a long-lived IAM user key. Encrypting
  etcd or an external store improves where it sits; only IRSA removes it, and IRSA needs EKS —
  [`docs/eks-and-irsa.md`](eks-and-irsa.md) designs it and this repository does not run it.
- **Two descriptions of the same platform.** `docker-compose.yml` and `k8s/` carry the same addresses
  and the same memory numbers, and nothing checks that they agree. Collapsing them means Helm or
  Kustomize, which means a templating language over manifests that are currently readable as-is.
- **Autoscaling is bounded by one machine.** Pods scale, nodes do not. On the t3.large the ceiling
  arrives fast, and beyond it the HPA produces `Pending` pods and no improvement.
- **A prediction written into the manifests was wrong, and the fix was to change the comment.** Step 10c
  expected `CrashLoopBackOff` to be the startup-ordering mechanism; every pod came up with 0 restarts,
  because Step 6a's config retry — added for a laptop — absorbed the wait inside the JVM. Worth keeping
  as an instance of the 9d rule pointing the other way: a measurement that contradicts the guess is also
  an action, not a footnote.

---

## Things I would do differently if starting over

- **Model `Author` from the first schema** (Step 1), rather than splitting it across two steps.
- **Write the outbox in Step 7a instead of promising it.** It is a table, a poller and forty lines. It
  was deferred because the step was already large, and the result is that the single most serious open
  defect is one that was understood at the time.
- **Put a `RoleHierarchy` in from Step 3.** Every authorization rule since names two roles because there
  isn't one.
- **Set up JaCoCo in Step 4.** Not for a badge — for the threshold that fails a build, which is the only
  part that changes behaviour.
- **Keep this file current per step.** It went seven steps without an update while `roadmap.md` and
  `decisions.md` were maintained. Step 9's entry above was written in the same commit as Step 9, which
  is what the rest of this list should have looked like. The material was never missing — every "What got worse" section in
  `bookstore-platform/README.md` is raw material for this document — but assembling it in one pass at
  Step 8 is exactly the "written at the end" failure the header warns about.

---

## What this project deliberately does not have

Stated so it is clear these are decisions rather than omissions:

- **No shared "common" library** between services (D12), which is why event contracts are duplicated by
  hand and why item 4 exists. The alternative — one jar every service depends on — turns a set of
  microservices into a distributed monolith, and that trade was made knowingly.
- **No exactly-once delivery anywhere.** At-least-once plus idempotent consumers, because exactly-once
  across a broker and a database does not exist without a transactional outbox on one side and dedupe on
  the other. Item 1 is the missing half.
- **No real payment gateway.** `charge()` declines any amount ending in `.13`. The saga is real; the
  money is not.
- **No email.** notification-service logs what it would send. The integration would add setup and no
  lesson.
- **No frontend.** The API is the deliverable, and CORS is configured for a frontend that does not exist
  yet.
