-- Demo data for local development only.
--
-- Two things make this file different from the schema migrations in db/migration:
--
-- 1. It lives in db/seed/, which only application-dev.yml adds to spring.flyway.locations. Prod never
--    loads it, so this fake data cannot reach real users.
--
-- 2. It is a REPEATABLE migration (the R__ prefix, no version number). Repeatable migrations run after
--    every versioned migration and carry no version of their own — so seed data never occupies a slot
--    in the schema's version sequence. Giving it a version (the original V900) pushed the database to
--    version 900 and made every subsequent real migration look out-of-order to Flyway.
--
-- Because a repeatable migration re-runs whenever its checksum changes, everything below MUST be
-- idempotent: running it a second time has to be a no-op, not a duplicate row or a constraint failure.

-- Authors. INSERT ... SELECT ... WHERE NOT EXISTS rather than ON CONFLICT, because `name` carries no
-- unique constraint — two real authors can legitimately share a name, so the schema must not forbid it.
INSERT INTO author (name)
SELECT v.name
FROM (VALUES
    ('Robert C. Martin'),
    ('Joshua Bloch'),
    ('Martin Kleppmann'),
    ('Eric Evans'),
    ('David Thomas & Andrew Hunt')
) AS v(name)
WHERE NOT EXISTS (SELECT 1 FROM author a WHERE a.name = v.name);

-- Books. ISBN is unique, so ON CONFLICT makes re-insertion a no-op.
INSERT INTO book (title, isbn, price, stock, created_at) VALUES
  ('Clean Code',                            '9780132350884', 42.50, 12, NOW()),
  ('Effective Java',                        '9780134685991', 49.99,  8, NOW()),
  ('Designing Data-Intensive Applications', '9781449373320', 55.00,  5, NOW()),
  ('Domain-Driven Design',                  '9780321125217', 61.75,  3, NOW()),
  ('The Pragmatic Programmer',              '9780135957059', 44.20,  0, NOW())
ON CONFLICT (isbn) DO NOTHING;

-- Link each book to its author. A plain UPDATE keyed on the natural keys is naturally idempotent:
-- running it again sets the same values.
UPDATE book SET author_id = (SELECT id FROM author WHERE name = 'Robert C. Martin')
  WHERE isbn = '9780132350884';
UPDATE book SET author_id = (SELECT id FROM author WHERE name = 'Joshua Bloch')
  WHERE isbn = '9780134685991';
UPDATE book SET author_id = (SELECT id FROM author WHERE name = 'Martin Kleppmann')
  WHERE isbn = '9781449373320';
UPDATE book SET author_id = (SELECT id FROM author WHERE name = 'Eric Evans')
  WHERE isbn = '9780321125217';
UPDATE book SET author_id = (SELECT id FROM author WHERE name = 'David Thomas & Andrew Hunt')
  WHERE isbn = '9780135957059';
