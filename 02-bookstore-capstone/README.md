# Capstone — Online Bookstore Platform

The course capstone: a bookstore that starts as one Spring Boot application and grows, step by step,
into a set of cooperating microservices deployed on AWS.

The point of the project is the **evolution**, not the end state. Each step exists because the previous
step created a problem it solves, and the Git history is meant to show that progression.

## Status

Currently at **Step 5 of 11** — see [docs/roadmap.md](docs/roadmap.md) for the full plan and where each
technology enters.

| Step | Delivers | Status |
|------|----------|--------|
| 1 | Monolith skeleton — Book CRUD, layering, validation, AOP | ☑ done |
| 2 | PostgreSQL, indexes, transactions, N+1, optimistic locking | ☑ done |
| 3 | Spring Security, JWT, USER/ADMIN roles | ☑ done |
| 4 | Testing — 50 tests: unit, repository, web, integration | ☑ done |
| 5 | Split into microservices | ☑ done — four services, Feign, circuit breaker, sagas both directions |
| 6–11 | Config · Kafka · Gateway · AWS · K8s · CI/CD | planned |

## Layout

```
02-bookstore-capstone/
├── docs/
│   ├── requirements.md   # the assignment brief, cleaned up — scope source of truth
│   ├── roadmap.md        # the 11 steps, why they are in this order, status tracker
│   ├── architecture.md   # current + target architecture diagrams (submission deliverable #4)
│   └── decisions.md      # why each non-obvious choice was made (interview answers live here)
├── bookstore-platform/   # the services (Steps 5-11)
│   ├── user-service/     # owns users; the only service that issues tokens
│   ├── book-service/     # owns the catalog and stock; verifies tokens, never mints them
│   ├── order-service/    # owns orders; calls book-service over HTTP for price and stock
│   └── payment-service/  # owns payments; the saga that rolls forward rather than back
├── docker-compose.yml    # one PostgreSQL per service
└── scripts/              # benchmark data, concurrency demo

The monolith (Steps 1-4) is preserved in Git at the `step-4-monolith` tag.
```

## Tech stack

| Concern | Choice | Why |
|---------|--------|-----|
| Language | Java 21 | Current LTS |
| Framework | Spring Boot 3.5.16 | Pinned to the Spring Cloud 2025.0.x release train — see [D1](docs/decisions.md) |
| Web | Spring Web (REST) | |
| Persistence | Spring Data JPA / Hibernate | |
| Database | PostgreSQL 17 (Docker) | |
| Schema | Flyway migrations | Hibernate runs as `validate` only — see [D6](docs/decisions.md) |
| Validation | Jakarta Bean Validation | |
| Cross-cutting | Spring AOP | |
| Boilerplate | Lombok | |

## Run it

Start both databases first:

```bash
docker compose up -d
```

Then each service, in its own terminal:

```bash
cd bookstore-platform/user-service && ../mvnw spring-boot:run
```

```bash
cd bookstore-platform/book-service && ../mvnw spring-boot:run
```

```bash
cd bookstore-platform/order-service && ../mvnw spring-boot:run
```

```bash
cd bookstore-platform/payment-service && ../mvnw spring-boot:run
```

user-service listens on 8081, book-service on 8082, order-service on 8083, payment-service on 8084. See
[bookstore-platform/test-platform.http](bookstore-platform/test-platform.http) for requests that cross
the boundary.

Run the tests — these need **nothing** running beforehand; Testcontainers starts and disposes of its
own PostgreSQL:

```bash
cd bookstore-platform && ./mvnw test
```

## API — Step 1

Every endpoint is public at this stage. Step 3 makes reads PUBLIC and writes ADMIN.

| Method | Path | Purpose | Success |
|--------|------|---------|---------|
| GET | `/api/books` | List / search books (`?keyword=`, `?page=`, `?size=`, `?sort=`) | 200 |
| GET | `/api/books/{id}` | Get one book | 200 |
| POST | `/api/books` | Create a book | 201 + `Location` |
| PUT | `/api/books/{id}` | Update a book | 200 |
| DELETE | `/api/books/{id}` | Delete a book | 204 |

Errors: `400` validation (with per-field messages), `404` missing book, `409` duplicate ISBN — all in one
consistent JSON envelope. See [bookstore/test.http](bookstore/test.http) for runnable requests covering
every case.
