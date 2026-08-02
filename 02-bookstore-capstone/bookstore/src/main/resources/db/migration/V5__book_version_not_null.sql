-- V5 — make book.version NOT NULL DEFAULT 0.
--
-- V1 declared `version BIGINT` with no default, which was fine while every row was created through
-- Hibernate: it sets the initial value itself. Rows inserted by plain SQL — the dev seed — got NULL,
-- and the first UPDATE of such a row failed on the version increment:
--
--   NullPointerException: Cannot invoke "java.lang.Long.longValue()" because "current" is null
--
-- surfacing as a 500 from POST /api/books/{id}/purchase and PUT /api/books/{id}, but only ever on a
-- seeded book. Books created through the API were unaffected, which is why it stayed hidden through
-- Step 2e's concurrency testing.
--
-- Fixed forward rather than by editing V1, which has already run elsewhere (see D8). The DEFAULT is
-- the part that matters most: it stops any future direct INSERT from recreating the same landmine.

UPDATE book SET version = 0 WHERE version IS NULL;

ALTER TABLE book ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE book ALTER COLUMN version SET NOT NULL;
