# Capstone — Online Bookstore Platform

The course capstone: a bookstore that starts as one Spring Boot application and grows, step by step,
into a set of cooperating microservices deployed on AWS.

The point of the project is the **evolution**, not the end state. Each step exists because the previous
step created a problem it solves, and the Git history is meant to show that progression.

## Status

Currently at **Step 1 of 11** — see [docs/roadmap.md](docs/roadmap.md) for the full plan and where each
technology enters.

| Step | Delivers | Status |
|------|----------|--------|
| 1 | Monolith skeleton — Book CRUD, layering, validation, AOP | ☑ done |
| 2 | PostgreSQL, indexes, transactions, N+1, optimistic locking | next |
| 3–11 | Security · Testing · Microservices · Config · Kafka · Gateway · AWS · K8s · CI/CD | planned |

## Layout

```
02-bookstore-capstone/
├── docs/
│   ├── requirements.md   # the assignment brief, cleaned up — scope source of truth
│   ├── roadmap.md        # the 11 steps, why they are in this order, status tracker
│   ├── architecture.md   # current + target architecture diagrams (submission deliverable #4)
│   └── decisions.md      # why each non-obvious choice was made (interview answers live here)
└── bookstore/            # the monolith (Steps 1-4); Step 5 splits it into services
```

## Tech stack

| Concern | Choice | Why |
|---------|--------|-----|
| Language | Java 21 | Current LTS |
| Framework | Spring Boot 3.5.16 | Pinned to the Spring Cloud 2025.0.x release train — see [D1](docs/decisions.md) |
| Web | Spring Web (REST) | |
| Persistence | Spring Data JPA / Hibernate | |
| Database | H2 now → PostgreSQL in Step 2 | |
| Validation | Jakarta Bean Validation | |
| Cross-cutting | Spring AOP | |
| Boilerplate | Lombok | |

## Run it

```bash
cd bookstore && ./mvnw spring-boot:run
```

Starts on `http://localhost:8080` under the `dev` profile: in-memory H2, schema generated from the
entities, seeded with five books. The H2 console is at `/h2-console`.

Run the tests:

```bash
cd bookstore && ./mvnw test
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
