# Capstone Roadmap — 11 Steps

The project is built in the order below. Each step has its own commit(s), so the Git history itself
shows the evolution from monolith to microservices (this is an explicit submission requirement).

Legend: ☐ not started · ◐ in progress · ☑ done

| Step | What it delivers | Key technologies | Status |
|------|------------------|------------------|--------|
| 1 | Monolith skeleton: Book CRUD, clean layering, AOP logging | Spring Boot, Spring Web, Lombok, Spring AOP | ☑ |
| 2 | Real data layer: PostgreSQL, indexes, transactions, N+1 fix | Spring Data JPA, Flyway, PostgreSQL, `@Version` | ☑ |
| 3 | Auth & security: register/login, JWT, USER/ADMIN roles | Spring Security, JJWT, BCrypt | ☑ |
| 4 | Testing: unit, web slice, repo slice, integration | JUnit 5, Mockito, Testcontainers | ☑ |
| 5 | Split into microservices, each with its own DB | OpenFeign, Resilience4j | ◐ |
| 6 | Central configuration | Spring Cloud Config Server | ☐ |
| 7 | Async messaging | Kafka, `@KafkaListener`, DLT | ☐ |
| 8 | Single front door | Spring Cloud Gateway | ☐ |
| 9 | Serverless file processing + browsing history | S3, Lambda, DynamoDB, SES/SNS | ☐ |
| 10 | Containerization & orchestration | Docker, Docker Compose, Kubernetes/EKS | ☐ |
| 11 | CI/CD & monitoring | GitHub Actions, Actuator, CloudWatch | ☐ |

## Why this order

The order is not arbitrary — each step exists because the previous step created a problem it solves:

- **1 → 2**: an in-memory app has no durability, no query plans, no concurrency story. Adding a real
  database forces you to think about keys, indexes, transactions, and locking.
- **2 → 3**: once data is real, "anyone can DELETE any book" becomes unacceptable. Security follows data.
- **3 → 4**: you now have logic worth protecting from regressions, and you are about to refactor
  aggressively (Step 5). Tests come *before* the refactor, not after — that is the whole point.
- **4 → 5**: splitting is where every distributed-systems problem appears at once: network calls fail,
  there is no shared transaction, identity must be propagated. You feel the problem before you learn
  the solution (Feign timeouts, circuit breakers, sagas).
- **5 → 6**: N services × M config values = a maintenance problem. Centralize it.
- **6 → 7**: synchronous chains couple services and make the customer wait for side effects. Events
  decouple them.
- **7 → 8**: N services means N addresses and N places to validate a token. Put auth at the edge.
- **8 → 9**: image processing is bursty and stateless — the textbook serverless workload. And browsing
  history is the textbook DynamoDB workload (high write volume, single-key access pattern).
- **9 → 10**: "works on my machine" × 8 services does not ship. Containerize and orchestrate.
- **10 → 11**: manual deploys and blind production do not scale. Automate and observe.

## Definition of Done per step

Each step is only "done" when its Definition of Done in [requirements.md](requirements.md) is met **and**
you can explain the design decision out loud — the assessment includes a demo video and a
"what would you improve" write-up, both of which reward understanding over line count.

## Deliverables (from the assignment)

1. Source code in Git with a step-by-step commit history.
2. A short demo video showing the platform in use.
3. A written "what I would improve" reflection — see [reflection.md](reflection.md).
4. A high-level architecture diagram covering frontend, backend, database, and cloud services —
   see [architecture.md](architecture.md).
