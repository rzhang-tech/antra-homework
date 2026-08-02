-- V2 — make the order durable before anything irreversible happens.
--
-- 5b wrote the order LAST, after reserving stock in book-service. That ordering has a hole nothing can
-- close from inside a single method: if the process dies between the reservation and the insert, stock
-- is gone, no order exists, and nothing anywhere knows. No log, no row, no way to notice.
--
-- The fix is not cleverer code — it is writing down the intent first. The order is committed as PENDING
-- before the first outbound call, so a crash at any later point leaves a row a recovery process can
-- find and finish or unwind. That is the difference between a saga and a sequence of hopeful calls.

-- Each line carries the id under which its stock was reserved, so compensation knows exactly what to
-- release. Nullable because it is assigned before the call and stays unconfirmed until it succeeds.
ALTER TABLE order_item ADD COLUMN reservation_id UUID;

-- PENDING now means "written down, stock not yet confirmed reserved" — an in-flight saga rather than a
-- finished order. AWAITING_PAYMENT means every reservation succeeded.
ALTER TABLE orders DROP CONSTRAINT orders_status_valid;
ALTER TABLE orders ADD CONSTRAINT orders_status_valid
    CHECK (status IN ('PENDING', 'AWAITING_PAYMENT', 'PAID', 'CANCELLED', 'SHIPPED', 'FAILED'));

-- When the saga last changed state. The recovery job asks "which orders have been PENDING for longer
-- than any healthy order ever is?" — those are the ones whose process died mid-flight.
ALTER TABLE orders ADD COLUMN state_changed_at TIMESTAMPTZ;
UPDATE orders SET state_changed_at = created_at WHERE state_changed_at IS NULL;
ALTER TABLE orders ALTER COLUMN state_changed_at SET NOT NULL;

CREATE INDEX idx_orders_status_state_changed ON orders (status, state_changed_at);
