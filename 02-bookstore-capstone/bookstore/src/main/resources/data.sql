-- Sample catalog, loaded on startup under the dev profile only.
INSERT INTO book (title, isbn, price, stock, cover_url, version, created_at) VALUES
  ('Clean Code',                          '9780132350884',  42.50, 12, NULL, 0, CURRENT_TIMESTAMP),
  ('Effective Java',                      '9780134685991',  49.99,  8, NULL, 0, CURRENT_TIMESTAMP),
  ('Designing Data-Intensive Applications','9781449373320', 55.00,  5, NULL, 0, CURRENT_TIMESTAMP),
  ('Domain-Driven Design',                '9780321125217',  61.75,  3, NULL, 0, CURRENT_TIMESTAMP),
  ('The Pragmatic Programmer',            '9780135957059',  44.20,  0, NULL, 0, CURRENT_TIMESTAMP);
