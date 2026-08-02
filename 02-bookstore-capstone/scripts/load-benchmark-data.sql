-- Loads 2,000 authors and 100,000 synthetic books so index behaviour is actually measurable.
--
-- Deliberately NOT a Flyway migration: it is slow, it is pure noise in the schema history, and it must
-- never run anywhere but a local benchmark database. Run it by hand:
--
--   docker exec -i bookstore-postgres psql -U bookstore -d bookstore < scripts/load-benchmark-data.sql
--
-- Re-runnable: it clears its own rows first. Synthetic rows are tagged BENCH- so they can be told apart
-- from the demo data and removed with:
--
--   DELETE FROM book WHERE isbn LIKE 'BENCH-%'; DELETE FROM author WHERE name LIKE 'Bench Author %';
--
-- The distribution matters. An earlier version spread 100k books over the five demo authors, giving
-- each 20% of the table — at that selectivity PostgreSQL correctly ignores an index, because reading a
-- fifth of the rows through an index costs more than just scanning the table. 2,000 authors puts each
-- at ~0.05%, which is what a filter on a foreign key actually looks like in production.

DELETE FROM book WHERE isbn LIKE 'BENCH-%';
DELETE FROM author WHERE name LIKE 'Bench Author %';

INSERT INTO author (name)
SELECT 'Bench Author ' || g FROM generate_series(1, 2000) AS g;

WITH numbered_author AS (
    SELECT id, row_number() OVER (ORDER BY id) - 1 AS rn
    FROM author WHERE name LIKE 'Bench Author %'
)
INSERT INTO book (title, isbn, price, stock, created_at, author_id)
SELECT
    'Book ' || g || ' on '
        || (ARRAY['Java', 'Python', 'Systems', 'Design', 'Data', 'Cloud', 'Testing'])[1 + (g % 7)],
    'BENCH-' || g,
    (random() * 90 + 10)::numeric(10, 2),
    (random() * 100)::int,
    NOW(),
    na.id
FROM generate_series(1, 100000) AS g
JOIN numbered_author na ON na.rn = g % 2000;

-- One book with a keyword that appears nowhere else, for testing a genuinely selective LIKE search.
UPDATE book SET title = 'The Zebra Compiler Handbook' WHERE isbn = 'BENCH-77';

-- Refresh the planner's statistics. Without this PostgreSQL may still be working from row counts taken
-- when the table was tiny, and will pick plans that look inexplicable.
ANALYZE book;
ANALYZE author;

SELECT
    (SELECT count(*) FROM book)   AS total_books,
    (SELECT count(*) FROM author) AS total_authors,
    (SELECT count(*) FROM book WHERE author_id = (SELECT id FROM author WHERE name = 'Bench Author 1'))
        AS books_per_author;
