# Capstone Requirements

Cleaned-up transcription of the assignment brief (`capstone-project.docx`). This is the source of truth
for scope; the original document stays untouched in `file/`.

## The product

An **online bookstore platform**: users browse and search books, register and log in, place orders, pay,
and receive confirmation. It starts as one application and grows into cooperating microservices deployed
to the cloud.

Core capabilities by the end:

- Users register, log in, and stay authenticated with a JWT.
- Anyone can browse/search the book catalog.
- A logged-in user places an order; stock is checked and reserved transactionally.
- Payment is processed; on success, notification and analytics services react asynchronously.
- Admins manage the catalog and upload book cover images, which are processed automatically.
- The whole system is containerized, deployed, monitored, and shipped through CI/CD.

**Frontend.** Optional and may be AI-generated. APIs are tested with Postman/curl.

## Roles

| Role | Meaning |
|------|---------|
| PUBLIC | No login required, including anonymous visitors |
| USER | Registered, logged-in customer (valid JWT, role `USER`) |
| ADMIN | Staff account (valid JWT, role `ADMIN`); manages the catalog, sees all orders |

## Database schema per service

Each service owns its **own** database. Types are indicative; columns may be customized.

### user-service — PostgreSQL

`users`: `id` BIGINT PK · `username` VARCHAR UNIQUE NOT NULL · `email` VARCHAR UNIQUE NOT NULL ·
`password_hash` VARCHAR NOT NULL (BCrypt, never plain text) · `role` VARCHAR NOT NULL (`USER`/`ADMIN`) ·
`created_at` TIMESTAMP

### book-service — PostgreSQL

`author`: `id` BIGINT PK · `name` VARCHAR NOT NULL

`book`: `id` BIGINT PK · `title` VARCHAR NOT NULL (indexed — frequently searched) ·
`author_id` BIGINT FK → `author(id)` (indexed — used in joins/filters) · `isbn` VARCHAR UNIQUE ·
`price` NUMERIC NOT NULL · `stock` INT NOT NULL (CHECK `stock >= 0`) · `cover_url` VARCHAR (S3 URL) ·
`version` BIGINT (`@Version`, optimistic locking on stock) · `created_at` TIMESTAMP

### order-service — PostgreSQL

`orders`: `id` BIGINT PK · `user_id` BIGINT NOT NULL (from the JWT, not a cross-DB FK) ·
`status` VARCHAR NOT NULL (`PENDING`/`PAID`/`CANCELLED`/`SHIPPED`) · `total_price` NUMERIC NOT NULL ·
`created_at` TIMESTAMP

`order_item`: `id` BIGINT PK · `order_id` BIGINT FK → `orders(id)` · `book_id` BIGINT NOT NULL
(references a book in book-service, by id only) · `quantity` INT NOT NULL · `unit_price` NUMERIC NOT NULL
(price captured at order time)

### payment-service — PostgreSQL

`payment`: `id` BIGINT PK · `order_id` BIGINT NOT NULL UNIQUE (one payment per order) ·
`amount` NUMERIC NOT NULL · `status` VARCHAR NOT NULL (`SUCCESS`/`FAILED`) · `paid_at` TIMESTAMP

> **Cross-service references.** `order_item.book_id` and `orders.user_id` are plain id values, **not**
> foreign keys — each service has its own database, so there are no cross-database FKs. A service that
> needs another's data calls that service's API.

### DynamoDB (Step 9)

`CoverMetadata` — PK `bookId` (String); attributes `s3Key`, `contentType`, `width`, `height`,
`sizeBytes`, `processedAt`

`UserBrowsingHistory` — PK `userId` (String, partition key), SK `viewedAt` (Number, epoch ms);
attributes `bookId`, `bookTitle`; TTL on `expireAt`

---

## Step 1 — Monolith Skeleton

**Goal.** One Spring Boot application with clean layers.

**Build.** Book CRUD; controller → service → repository layering; constructor injection; validation on
DTOs; global exception handler (`@RestControllerAdvice` → 400/404/409); dev and prod profiles.
Commit to Git from the start.

**AOP.** A single `LoggingAspect` with `@Around` advice on the service layer logging method name,
arguments, and execution time. Carried into every microservice later.

**APIs** (all public at this stage; roles arrive in Step 3):
`GET /api/books` (list/search, paging + keyword) · `GET /api/books/{id}` · `POST /api/books` ·
`PUT /api/books/{id}` · `DELETE /api/books/{id}`

**Done when.** App builds and runs; CRUD works via curl/Postman; layers cleanly separated.

## Step 2 — Data Layer

**Goal.** A real relational database with proper schema, indexing, and transactional integrity.

**Build.** PostgreSQL via Docker; Book + Author with proper keys; an index on a frequently-queried column
with a justification; `@Transactional` around multi-step writes; deliberately create then fix an **N+1
query** (list authors with their books) using `JOIN FETCH`/entity graph, confirmed with SQL logging;
`EXPLAIN ANALYZE` on the heaviest query showing an index scan; optimistic locking (`@Version`) on stock.
Flyway migrations under `db/migration`.

**Done when.** Data persists in PostgreSQL; correct keys plus one justified index; a transaction protects
a multi-step write; you can show the EXPLAIN plan and explain the N+1 fix.

## Step 3 — Authentication & Security

**Goal.** Register and log in; protected actions require a valid JWT; admin actions require `ADMIN`.

**Build.** `User` entity; register/login endpoints; BCrypt password hashing; JWT issued on login and
validated in a `JwtAuthenticationFilter`; stateless `SecurityFilterChain`; roles `USER` and `ADMIN`
enforced by route rules and/or `@PreAuthorize`. Catalog reads stay PUBLIC, catalog writes become ADMIN,
ordering becomes USER. Optional: "Login with Google" via OAuth2/OIDC.

**APIs.** `POST /api/auth/register` (PUBLIC) · `POST /api/auth/login` (PUBLIC) · `GET /api/auth/me`
(USER/ADMIN)

**Done when.** Passwords are BCrypt-hashed; protected endpoints return 401 without a token and 403 for
the wrong role; tokens expire; you can explain how the server validates a token.

## Step 4 — Testing

**Goal.** Protect the system with automated tests *before* splitting and deploying it.

**Build.** Unit tests for service logic (repository mocked with Mockito); `@WebMvcTest` for the web layer;
`@DataJpaTest` for persistence; one `@SpringBootTest` integration test against a Testcontainers
PostgreSQL; security tests asserting 401/403. Optional: a Postman/Newman collection runnable in CI.

**Done when.** The suite runs with one command and passes; it covers business logic, the web layer, and
at least one full integration path; broken logic fails a test.

## Step 5 — Split into Microservices

**Goal.** Independent services, each with its own database.

**Build.** Split into **user-service**, **book-service**, **order-service**, **payment-service**.
order-service calls book-service (price/stock) via **OpenFeign** with an explicit timeout. Add
**Resilience4j**: circuit breaker + fallback, plus retry for transient failures — killing book-service
must degrade gracefully, not cascade. Propagate the user's identity (forward the JWT). Challenge:
design or implement a **saga / eventual-consistency** approach for order + payment + stock.

**Order APIs.** `POST /api/orders` (USER) · `GET /api/orders` (USER) · `GET /api/orders/{id}`
(USER own / ADMIN) · `GET /api/orders/all` (ADMIN) · `PUT /api/orders/{id}/cancel` (USER own / ADMIN)

**User APIs.** `POST /api/auth/register` · `POST /api/auth/login` · `GET /api/users/me` ·
`GET /api/users` (ADMIN) · `GET /api/users/{id}` (ADMIN)

**Payment APIs.** `POST /api/payments` (USER, own order) · `GET /api/payments/{orderId}` (USER own / ADMIN)

**Done when.** Services run independently, each with its own DB; order-service calls book-service over
HTTP with a timeout and circuit breaker; killing book-service gives a graceful fallback, not a cascade.

## Step 6 — Central Configuration Management

**Goal.** One place to change and audit configuration for all services.

**Build.** A **Spring Cloud Config Server** (`@EnableConfigServer`) serving a `config-repo` of per-service
`.yml` files plus a shared `application.yml`. Each microservice pulls its config on startup via
`spring.config.import`. Secrets stay out of plain config (environment variables or a secrets store).

> In a real Kubernetes deployment ConfigMaps and Secrets usually fill this role. The Config Server is
> built here to understand the classic Spring Cloud approach; knowing both is expected in interviews.

**Done when.** The config server serves per-service configuration; each service loads it on startup; a
centrally-changed value is picked up; you can explain how this maps to K8s ConfigMaps.

## Step 7 — Asynchronous Messaging with Kafka

**Goal.** Decouple an order's side effects from the order request itself.

**Build.** Kafka via Docker. order-service publishes `OrderPlaced`; payment-service publishes
`PaymentCompleted`. **notification-service** consumes `OrderPlaced` in its own consumer group and logs a
confirmation; **analytics-service** consumes the same topic under a **different consumer group**
(broadcast demo). Key messages by order id to preserve per-order ordering. Make consumers **idempotent**
(at-least-once means redelivery). Challenge: a Dead Letter Topic plus a DLQ-depth monitor.

These two services expose no public REST APIs — only `/actuator/health`.

**Done when.** Placing an order returns immediately while notification/analytics react via Kafka; two
independent consumer groups both receive events; a redelivered message does not double-process.

## Step 8 — API Gateway

**Goal.** One front door; authentication at the edge.

**Build.** **Spring Cloud Gateway** routing `/api/auth/**` and `/api/users/**` → user-service,
`/api/books/**` → book-service, `/api/orders/**` → order-service, `/api/payments/**` → payment-service.
Validate the **JWT at the gateway** and forward identity downstream. Configure **CORS** once, here.

**Done when.** All client traffic goes through one address; routing works; unauthenticated requests to
protected routes are rejected at the edge.

## Step 9 — File Processing (S3 + Lambda + DynamoDB)

**Goal.** Two DynamoDB-backed features, deployed to real AWS.

### Feature A — cover upload → process → email

Admin uploads a cover to **S3** → the `ObjectCreated` event triggers a **Lambda** → the Lambda extracts
metadata (size, dimensions, content type) → writes it to **DynamoDB** (`CoverMetadata`, keyed by
`bookId`) → publishes to **SNS** (or sends via **SES**) so an admin gets a "cover for book X processed"
email.

Must be **idempotent** (deterministic key / conditional write) so a redelivered S3 event or a re-upload
creates no duplicate record and no duplicate email. Challenge: a Lambda DLQ plus monitoring, and one cost
optimization such as an S3 lifecycle policy for old covers.

### Feature B — user browsing history

Every time a logged-in user views a book, write a history entry to **UserBrowsingHistory**
(PK `userId`, SK `viewedAt`) asynchronously so it does not slow the read. An endpoint returns the user's
recent views, newest first via the sort key. Justify the partition key (`userId` spreads load evenly, no
hot partition) and set a **TTL** so history older than ~30 days auto-deletes.

**APIs (book-service).** `POST /api/books/{id}/cover` (ADMIN) · `GET /api/books/{id}/cover` (PUBLIC) ·
`GET /api/books/me/history` (USER)

**Done when.** Uploading a cover produces metadata in DynamoDB via Lambda **and** a confirmation email;
duplicates and duplicate emails are prevented; viewing books as a logged-in user records history and
"recently viewed" returns it newest-first; you can justify both partition keys.

## Step 10 — Containerization & Orchestration

**Goal.** Every service in a container; the whole system on Kubernetes.

**Build.** A **multi-stage Dockerfile** per service (JDK build stage → JRE runtime stage);
**docker-compose** running the full local stack (all services + PostgreSQL + Kafka) with one command;
**Kubernetes manifests** — a Deployment (with replicas) and a Service per microservice; config
externalized via **ConfigMap/Secret**; **liveness/readiness probes** wired to Actuator health endpoints.
Challenge: a Horizontal Pod Autoscaler, and a design note mapping this to **AWS EKS** with **IRSA**.

**Done when.** Each service has a working image; the full stack runs via docker-compose; K8s manifests
define Deployments, Services, config, and probes.

## Step 11 — CI/CD & Monitoring

**Goal.** Automate build/test/deploy and observe the running system.

**Build.** A **GitHub Actions** pipeline: on push/PR → build, run tests, build the Docker image (a failing
test fails the pipeline), push images tagged by commit SHA, then deploy (or design the deploy stage with
a manual approval gate before prod). **Actuator** on every service with health endpoints exposed and
secured. Define the **metrics** to monitor per service (request rate, error rate, p99 latency, Kafka
consumer lag, DB connections) and which to **alarm** on (routed via SNS/Slack). Challenge: rolling /
blue-green / canary deploys with a rollback path, and metrics on a dashboard (Micrometer → Prometheus /
Grafana, or CloudWatch).

**Done when.** Every commit triggers automated build + test; the pipeline produces a versioned image; an
automated deploy with rollback exists or is designed; health endpoints and a defined set of
metrics/alarms exist.

---

## Deliverables & Assessment

1. **Source code** in Git, with a commit history showing the step-by-step evolution.
2. **A short video** demonstrating how to use the platform.
3. **A written reflection**: what would you improve, based on what you completed.
4. **A diagram** of the high-level architecture — frontend, backend, database, and everything together.
