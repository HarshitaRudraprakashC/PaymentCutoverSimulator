-- V5: settlement and reconciliation.

-- Counts captures the processor definitely refused. The rollout gate uses it
-- to work out the NEW processor's failure rate.
ALTER TABLE payments ADD COLUMN capture_failures INT NOT NULL DEFAULT 0;

-- One row per reconciliation run. The gate checks that a run finished recently.
CREATE TABLE recon_runs (
    id               BIGSERIAL    PRIMARY KEY,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,                       -- NULL while running
    payments_checked INT          NOT NULL DEFAULT 0,
    mismatches       INT          NOT NULL DEFAULT 0    -- problems seen in this run (old or new)
);

-- One row per problem found. Resolving one only records who and why: it never changes money.
CREATE TABLE recon_mismatches (
    id              BIGSERIAL    PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES recon_runs (id),
    payment_id      UUID         NOT NULL REFERENCES payments (id),
    processor       TEXT         NOT NULL CHECK (processor IN ('OLD', 'NEW')),
    type            TEXT         NOT NULL CHECK (type IN
                        ('MISSING_INTERNAL', 'MISSING_AT_PROCESSOR', 'DUPLICATE_CAPTURE',
                         'AMOUNT_MISMATCH', 'LEDGER_MISMATCH')),
    expected        TEXT         NOT NULL,
    actual          TEXT         NOT NULL,
    resolved        BOOLEAN      NOT NULL DEFAULT false,
    resolved_by     TEXT,
    resolution_note TEXT,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One row per payment per kind of problem. Running reconciliation again doesn't report it twice,
-- and once a person has resolved it, it stays resolved (it isn't re-opened on the next run).
CREATE UNIQUE INDEX uq_mismatch_payment_type ON recon_mismatches (payment_id, type);

-- The gate asks "how many open problems on NEW?" on every rollout change.
CREATE INDEX idx_open_mismatch_processor ON recon_mismatches (processor) WHERE NOT resolved;