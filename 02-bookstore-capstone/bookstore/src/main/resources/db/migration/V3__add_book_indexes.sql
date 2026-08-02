-- V3 — indexes on the columns the catalog actually filters by.
--
-- Every index is a trade: it speeds reads and slows writes (each INSERT/UPDATE must maintain it) and it
-- costs disk. So each one below has to justify itself against a real query.

-- 1. book.author_id
--
-- PostgreSQL indexes a PRIMARY KEY automatically. It does NOT index a FOREIGN KEY column — a very
-- common surprise. This column is filtered on ("books by this author"), joined on, and read by the
-- referential-integrity check that runs on every DELETE FROM author. At ~50 books per author out of
-- 100k, it is highly selective, which is exactly when an index pays off.
CREATE INDEX idx_book_author_id ON book (author_id);

-- 2. book.title
--
-- Serves exact matches and prefix searches (LIKE 'Clean%'). A B-tree is ordered, so it can seek to a
-- known prefix — but it cannot help a search that starts with a wildcard, which is what index 3 is for.
CREATE INDEX idx_book_title ON book (title);

-- 3. lower(book.title), trigram
--
-- The catalog's search endpoint issues `lower(title) LIKE lower('%keyword%')`. The leading wildcard
-- makes index 2 useless: a B-tree is sorted by the start of the string, and this query has no known
-- start. pg_trgm indexes every three-character sequence in the value instead, so a substring match
-- becomes a lookup rather than a scan of every row.
--
-- Indexing the expression `lower(title)` rather than `title` matters: an expression index is only used
-- when the query's expression matches it exactly.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_book_title_trgm ON book USING GIN (lower(title) gin_trgm_ops);
