-- V2: the double-entry ledger.
-- Every money movement is written as a journal: rows that share a journal_id
-- and whose debits equal their credits. Rows are only ever added, never changed.

CREATE TABLE ledger_entries (
    id           BIGSERIAL    PRIMARY KEY,
    journal_id   UUID         NOT NULL,
    payment_id   UUID         NOT NULL REFERENCES payments (id),
    entry_type   TEXT         NOT NULL CHECK (entry_type IN ('CAPTURE', 'SETTLEMENT')),
    account      TEXT         NOT NULL,       -- e.g. PROCESSOR_RECEIVABLE:OLD, MERCHANT_PAYABLE
    direction    TEXT         NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor BIGINT       NOT NULL CHECK (amount_minor > 0),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Safety net: a payment can have only ONE capture debit and ONE capture credit.
    -- Even a bug that tries to post the capture twice is stopped by the database.
    UNIQUE (payment_id, entry_type, direction)
);

CREATE INDEX idx_ledger_journal ON ledger_entries (journal_id);