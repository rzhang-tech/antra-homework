# User System — Spring Boot REST API

A small user-management REST API built on the same layered architecture as the course's Book demo.
It exposes 6 endpoints for full CRUD over a `User` resource, with request validation, a DTO layer,
and centralized exception handling.

## Tech stack

| Concern | Choice |
|---------|--------|
| Framework | Spring Boot 4.1.0 |
| Language | Java 17 |
| Web | Spring Web (REST) |
| Persistence | Spring Data JPA (Hibernate) |
| Database | H2 (in-memory) |
| Validation | Jakarta Bean Validation |
| Boilerplate | Lombok |

## How to run

```bash
./mvnw spring-boot:run
```

The app starts on `http://localhost:8080`. H2 runs in memory, so data resets on every restart.
See `test.http` for ready-to-run requests covering every endpoint (including the 404 / 409 / 400 cases).

## API endpoints

| # | Method | Path | Purpose | Success status |
|---|--------|------|---------|----------------|
| 1 | POST | `/api/users` | Create a user | 201 Created |
| 2 | GET | `/api/users` | List all users | 200 OK |
| 3 | GET | `/api/users/{id}` | Get one user | 200 OK |
| 4 | GET | `/api/users/search?keyword=` | Search by username | 200 OK |
| 5 | PUT | `/api/users/{id}` | Update a user | 200 OK |
| 6 | DELETE | `/api/users/{id}` | Delete a user | 204 No Content |

Error responses: `404` for a missing user, `409` for a duplicate username/email, `400` for validation
failures — all returned as a consistent JSON error envelope.

---

# Assignment 1 — Workflow & Reasoning

This section documents *how* the project was built, not just what it contains. The whole point is the
professional thought process: how you decompose "build a user system with 6 APIs" into ordered,
buildable steps.

## The core idea: layered architecture, built inside-out

A REST request always flows through the same layers:

```
Client → Controller (HTTP) → Service (business rules) → Repository (database) → DB
```

Each layer has exactly one responsibility, so each has exactly one reason to change. Because an outer
layer depends on the inner one (Controller calls Service, Service calls Repository, everything operates
on the User data), you build **from the inside out** — the thing being depended on first. That way each
layer compiles as soon as it is written, and nothing ever references a class that doesn't exist yet.

## Step 0 — Set up the toolbox (dependencies)

- **What:** generate the project with 5 dependencies (Spring Web, Spring Data JPA, H2, Lombok, Validation).
- **Why:** a professional doesn't reinvent HTTP handling, DB access, or validation. Each dependency is
  one capability that a later layer will use: Web → Controller, Data JPA → Repository, H2 → storage,
  Validation → input checking, Lombok → less boilerplate. You acquire all the tools up front.

## Step 1 — Define what a "User" is (Entity)

- **What:** write the `User` class, list its fields (username, email, password, fullName, age), and use
  annotations to declare that it maps to the `users` table.
- **Why first:** every operation in the system acts on a user. The Repository stores users, the Service
  processes users, the Controller returns users. You must define the "user" before there is anything to
  operate on. It is the innermost core that everything depends on, so it is built first.
- **Why it doubles as the table definition:** the data is stored in a database. Rather than hand-writing
  a `CREATE TABLE` statement separately, ORM (the "translation bridge" between objects and tables) lets
  this one class serve as the table definition too — one piece of code does two jobs.

## Step 2 — How to store/retrieve users (Repository)

- **What:** write the `UserRepository` interface.
- **Why:** once "user" is defined, you need a place to save it to and read it from the database. Spring
  Data JPA lets you declare an interface (no implementation) and get CRUD methods for free by extending
  `JpaRepository<User, Long>`; custom lookups are generated just from a method name (e.g.
  `findByUsername`).

## Step 3 — The data shells (DTO)

- **What:** write `UserRequestDto` (incoming) and `UserResponseDto` (outgoing).
- **Why:** the database entity must not be exposed directly to the outside world. The clearest example:
  a password may be sent *in* (request) but must never be sent *out* (response), so the two directions
  use two different shells. Validation rules also live on the request DTO.

## Step 4 — Business rules (Service)

- **What:** write `UserService` / `UserServiceImpl` — enforce rules like "username must be unique" and
  "throw 404 if not found", and map between entity and DTO.
- **Why:** HTTP concerns belong to the Controller, database concerns to the Repository; the rules in the
  middle — *what is allowed and what should happen* — belong to the Service. Keeping the three separate
  means each can change independently and be tested on its own.

## Step 5 — The 6 public APIs (Controller)

- **What:** write `UserController`, mapping 6 HTTP requests to 6 methods.
- **Why:** this is what the assignment ultimately asks for — the interface real callers hit. It sits at
  the outermost layer, so it is written last. Each method is tiny: read the request, call the Service,
  wrap the result with the right status code. No business logic lives here.

## Step 6 — Consistent error handling (Exception layer)

- **What:** a global exception handler that turns "user not found" into a clean 404 JSON, "duplicate"
  into 409, and validation failures into 400.
- **Why:** so every error returns the same tidy shape instead of an ugly stack trace. The Service just
  throws meaningful exceptions; converting them to HTTP status codes + JSON is entirely this layer's job.

## Step 7 — Run & test

- **What:** start the app and call all 6 APIs (plus the 404 / 409 / 400 cases) one by one.
- **Why:** code that compiles is not code that works. Every endpoint must be exercised and observed to
  behave as expected. See `test.http` for the exact requests used to verify this project.

## The whole thing in one picture

```
Step 0  Toolbox (dependencies)  —— gather materials before laying the foundation
Step 1  User        (what it is)      ─┐
Step 2  Repository  (how to store)     │  inside-out:
Step 3  DTO         (in/out shells)    │  each layer depends only on the one inside it,
Step 4  Service     (what rules)       │  so it compiles as soon as it's written
Step 5  Controller  (the 6 APIs)      ─┘
Step 6  Exception layer  (error safety net)
Step 7  Run & test
```

## Key concepts this assignment exercises

- **IoC / Dependency Injection** — the container creates and wires the beans; classes declare
  dependencies via constructor injection (`final` fields + Lombok `@RequiredArgsConstructor`).
- **ORM (JPA / Hibernate)** — the entity's annotations are the rules that let Hibernate translate
  objects to SQL automatically, so no SQL is hand-written.
- **Separation of concerns** — Controller / Service / Repository each own one responsibility.
- **DTO pattern** — decouples the API contract from the database entity; enforces "password in, never out".
- **Layered exception handling** — business exceptions become correct HTTP status codes in one place.
