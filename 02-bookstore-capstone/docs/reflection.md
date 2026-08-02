# Reflection — What I Would Improve

Submission deliverable #3. Filled in step by step as gaps appear, so the final write-up is grounded in
things actually hit during the build rather than assembled at the end.

## Step 1 — monolith skeleton

**Known gaps, deliberately deferred**

- **The ISBN uniqueness check has a race condition.** `BookServiceImpl.create` and `.update` do a
  check-then-act: `existsByIsbn(...)` and then `save(...)`. Two concurrent requests creating the same
  ISBN can both pass the check; the database's unique constraint then rejects the second one with a
  `DataIntegrityViolationException`, which no handler maps — so the client sees 500 instead of 409.
  Deferred to Step 2, where a real PostgreSQL makes the failure reproducible and the fix (a
  `DataIntegrityViolationException` handler as a backstop) can be demonstrated rather than asserted.

- **Keyword search will not scale.** `findByTitleContainingIgnoreCase` produces `LIKE '%keyword%'`. The
  leading wildcard makes a B-tree index on `title` unusable, so this is a full table scan on any real
  catalog. Step 2 revisits it with `EXPLAIN ANALYZE`; the real fix is a PostgreSQL trigram (`pg_trgm`)
  or full-text index, or a search engine if the catalog grows.
- **No pagination cap.** `?size=100000` is accepted. A `max-page-size` limit belongs here before the API
  is public.
- **`data.sql` + `ddl-auto: create-drop` is a dev crutch.** Fine for a local demo, wrong for anything
  shared — Step 2 moves schema and seed data into versioned Flyway migrations.
- **Everything is public.** Anyone can `DELETE /api/books/{id}`. Step 3.
- **Only a context-loads test.** Real coverage is Step 4 — deliberately before the Step 5 refactor, since
  tests you write after a refactor only prove what the refactor produced.

**What I would do differently if starting over**

- Model the `Author` relation from the beginning rather than adding it in Step 2. Splitting it across
  steps made the first schema slightly artificial.
- The `PageResponseDto` mapper is generic but every caller passes the same mapping function; a small
  amount of that generality is unused.
