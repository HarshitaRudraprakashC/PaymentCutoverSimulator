package org.payment.processor;

public enum FailureMode {
    NONE,
    FAIL_BEFORE,            // fails WITHOUT capturing: safe to retry
    TIMEOUT_AFTER_SUCCESS,  // captures, then the reply is "lost": outcome unknown to us
    CRASH_AFTER_SUCCESS,    // captures, then OUR service "crashes" before saving anything
    LATENCY,                // answers, but slowly (2 seconds)
    UNDER_CAPTURE           // captures 1 cent less than asked: the planted bug for the migration run
}
