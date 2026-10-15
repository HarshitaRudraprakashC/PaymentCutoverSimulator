-- V6: Kafka events through a transactional outbox.

-- Events waiting to be sent to Kafka. Written in the SAME transaction as the payment change.
CREATE TABLE outbox_events (
    id              BIGSERIAL    PRIMARY KEY,                -- also the send order
    event_id        UUID         NOT NULL UNIQUE,
    payment_id      UUID         NOT NULL REFERENCES payments (id),
    payment_version INT          NOT NULL,                   -- the payment's version AFTER the change
    type            TEXT         NOT NULL CHECK (type IN ('PAYMENT_CAPTURED', 'PAYMENT_SETTLED')),
    payload         JSONB        NOT NULL,                   -- exactly what goes on the topic
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ                              -- NULL = not sent yet
);
-- The relay only ever looks for unsent rows. A partial index keeps that fast as the table grows.
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- ---- The consumer's side. Imagine these belong to another team's service. ----

-- Every event id the consumer has handled. A second delivery of the same id is skipped.
CREATE TABLE processed_events (
    event_id     UUID         PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The consumer's view of each payment: its latest state and version.
-- No foreign key to payments on purpose: this side only learns about payments through Kafka.
CREATE TABLE payment_projection (
    payment_id   UUID         PRIMARY KEY,
    merchant_id  TEXT         NOT NULL,
    status       TEXT         NOT NULL CHECK (status IN ('CAPTURED', 'SETTLED')),
    amount_minor BIGINT       NOT NULL,
    version      INT          NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Each merchant's balance, worked out from the projection.
CREATE VIEW merchant_balances AS
SELECT merchant_id,
       COALESCE(SUM(amount_minor) FILTER (WHERE status = 'CAPTURED'), 0) AS captured_minor,
       COALESCE(SUM(amount_minor) FILTER (WHERE status = 'SETTLED'), 0)  AS settled_minor
  FROM payment_projection
 GROUP BY merchant_id;

-- Reconciliation gets one more kind of problem: the Kafka-built view disagrees with our records.
ALTER TABLE recon_mismatches DROP CONSTRAINT recon_mismatches_type_check;
ALTER TABLE recon_mismatches ADD CONSTRAINT recon_mismatches_type_check CHECK (type IN
    ('MISSING_INTERNAL', 'MISSING_AT_PROCESSOR', 'DUPLICATE_CAPTURE',
     'AMOUNT_MISMATCH', 'LEDGER_MISMATCH', 'PROJECTION_DRIFT'));

-- Backfill: payments captured or settled BEFORE this migration have no events yet.
-- Give each one an event, so the Kafka view starts complete instead of reporting drift.
INSERT INTO outbox_events (event_id, payment_id, payment_version, type, payload)
SELECT e.event_id, e.id, e.version, e.type,
       jsonb_build_object(
           'eventId',        e.event_id,
           'type',           e.type,
           'paymentId',      e.id,
           'paymentVersion', e.version,
           'merchantId',     e.merchant_id,
           'amountMinor',    e.amount_minor,
           'currency',       e.currency,
           'occurredAt',     to_char(e.updated_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
  FROM (SELECT gen_random_uuid() AS event_id, p.*,
               CASE p.status WHEN 'SETTLED' THEN 'PAYMENT_SETTLED' ELSE 'PAYMENT_CAPTURED' END AS type
          FROM payments p
         WHERE p.status IN ('CAPTURED', 'SETTLED')) e;