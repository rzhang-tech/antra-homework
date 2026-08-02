-- V2 — make stock reservation idempotent.
--
-- The problem this solves is not theoretical. order-service calls POST /api/books/{id}/purchase over a
-- network. book-service commits the decrement, and the response is lost on the way back. The caller
-- sees a timeout and CANNOT TELL whether the stock was taken. Retrying sells the book twice; not
-- retrying leaves an order that failed with stock already gone. Neither is right, because the caller is
-- being asked a question it has no way to answer.
--
-- A reservation id fixes it at the source: the caller decides the id before making the call, so a
-- repeat is recognisable. book-service records what it did under that id and, on seeing it again,
-- reports the same outcome without acting twice. The ambiguity disappears and retry becomes safe —
-- which is what lets order-service turn retry back on for the write in 5d.

CREATE TABLE stock_reservation (
    -- Chosen by the CALLER, not generated here. That is the whole mechanism: an id the caller can
    -- reuse across attempts is what makes two attempts recognisable as one intent.
    id           UUID           PRIMARY KEY,

    book_id      BIGINT         NOT NULL REFERENCES book (id),
    quantity     INTEGER        NOT NULL,

    -- ACTIVE: stock is held. RELEASED: it was given back (order cancelled, or a saga compensating).
    status       VARCHAR(20)    NOT NULL,

    created_at   TIMESTAMPTZ    NOT NULL,
    released_at  TIMESTAMPTZ,

    CONSTRAINT stock_reservation_quantity_positive CHECK (quantity > 0),
    CONSTRAINT stock_reservation_status_valid CHECK (status IN ('ACTIVE', 'RELEASED'))
);

-- Reservations for a book, for reconciliation: "what is being held, and by whom".
CREATE INDEX idx_stock_reservation_book_id ON stock_reservation (book_id);

-- Old reservations that were never released — the query a recovery job runs.
CREATE INDEX idx_stock_reservation_status_created ON stock_reservation (status, created_at);
