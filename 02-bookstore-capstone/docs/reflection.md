# Reflection — What I Would Improve

Submission deliverable #3. Filled in step by step as gaps appear, so the final write-up is grounded in
things actually hit during the build rather than assembled at the end.

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
