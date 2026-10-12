-- V3: idempotency keys.
-- One row per (merchant, key). The primary key is what stops two identical
-- requests from both getting through: only one INSERT can win.

CREATE TABLE idempotency_keys (
    merchant_id   TEXT         NOT NULL,
    key           TEXT         NOT NULL,
    request_hash  TEXT         NOT NULL,          -- SHA-256 of the request; spots a key reused for a different request
    state         TEXT         NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    locked_until  TIMESTAMPTZ  NOT NULL,          -- lease: used in step 5 to recover from crashes
    payment_id    UUID,
    response_code INT,                            -- the HTTP status we answered with
    response_body JSONB,                          -- the exact body we answered with
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, key)
);