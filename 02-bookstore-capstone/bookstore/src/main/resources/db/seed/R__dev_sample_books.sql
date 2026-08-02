-- Demo data for local development only.
--
-- Two things make this file different from the schema migrations in db/migration:
--
-- 1. It lives in db/seed/, which only application-dev.yml adds to spring.flyway.locations. Prod never
--    loads it, so these five fake books cannot reach real users.
--
-- 2. It is a REPEATABLE migration (the R__ prefix, no version number). Repeatable migrations run after
--    every versioned migration and carry no version of their own — so seed data never occupies a slot
--    in the schema's version sequence. Giving it a version (the original V900) pushed the database to
--    version 900 and made every subsequent real migration look out-of-order to Flyway.
--
-- Because a repeatable migration re-runs whenever its checksum changes, it MUST be idempotent.
-- ON CONFLICT DO NOTHING makes re-execution a no-op instead of a duplicate-key failure.

INSERT INTO book (title, isbn, price, stock, created_at) VALUES
  ('Clean Code',                            '9780132350884', 42.50, 12, NOW()),
  ('Effective Java',                        '9780134685991', 49.99,  8, NOW()),
  ('Designing Data-Intensive Applications', '9781449373320', 55.00,  5, NOW()),
  ('Domain-Driven Design',                  '9780321125217', 61.75,  3, NOW()),
  ('The Pragmatic Programmer',              '9780135957059', 44.20,  0, NOW())
ON CONFLICT (isbn) DO NOTHING;
