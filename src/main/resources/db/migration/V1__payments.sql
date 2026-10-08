-- V1: the payments table.
-- Flyway runs this once, on first startup, and records that it ran.
-- Never edit this file after it has run: add V2__..., V3__... files instead.

CREATE TABLE payments (
    id               UUID         PRIMARY KEY,
    merchant_id      TEXT         NOT NULL,
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),    -- cents: 10000 = €100.00
    currency         CHAR(3)      NOT NULL,
    processor        TEXT         NOT NULL CHECK (processor IN ('OLD', 'NEW')),
    status           TEXT         NOT NULL CHECK (status IN
                         ('AUTHORIZED', 'CAPTURE_PENDING', 'CAPTURED', 'SETTLED', 'FAILED')),
    version          INT          NOT NULL DEFAULT 0,                   -- goes up on every change
    capture_attempts INT          NOT NULL DEFAULT 0,
    pending_since    TIMESTAMPTZ,                                       -- set while CAPTURE_PENDING
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- The resolver looks for stuck payments; settlement and recon look up by status.
CREATE INDEX idx_payments_status ON payments (status);
CREATE INDEX idx_payments_merchant ON payments (merchant_id);
