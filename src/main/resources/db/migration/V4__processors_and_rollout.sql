-- V4: the two fake processors' own records, and the rollout setting.

-- What the OLD processor has captured. No duplicate check: the same reference twice = charged twice.
CREATE TABLE old_processor_txns (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    reference    TEXT         NOT NULL,                 -- "<paymentId>:capture"
    type         TEXT         NOT NULL CHECK (type IN ('CAPTURE')),
    amount_minor BIGINT       NOT NULL CHECK (amount_minor > 0),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_old_txns_reference ON old_processor_txns (reference);

-- What the NEW processor has captured. Same shape, plus a duplicate check.
CREATE TABLE new_processor_txns (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    reference    TEXT         NOT NULL,
    type         TEXT         NOT NULL CHECK (type IN ('CAPTURE')),
    amount_minor BIGINT       NOT NULL CHECK (amount_minor > 0),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (reference, type)                            -- the one difference that matters
);

-- One row: what percentage of merchants go to the NEW processor.
CREATE TABLE rollout_config (
    id          INT          PRIMARY KEY CHECK (id = 1),
    new_percent INT          NOT NULL CHECK (new_percent BETWEEN 0 AND 100),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
INSERT INTO rollout_config (id, new_percent) VALUES (1, 0);