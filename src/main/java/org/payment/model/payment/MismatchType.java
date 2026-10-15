package org.payment.model.payment;

public enum MismatchType {

    MISSING_INTERNAL,
    MISSING_AT_PROCESSOR,
    DUPLICATE_CAPTURE,
    AMOUNT_MISMATCH,
    LEDGER_MISMATCH,
    PROJECTION_DRIFT;
}
