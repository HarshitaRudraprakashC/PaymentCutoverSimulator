package org.payment.model.admin;

public enum FailureMode {

    NONE,
    FAIL_BEFORE,
    TIMEOUT_AFTER_SUCCESS,
    CRASH_AFTER_SUCCESS,
    LATENCY,
    UNDER_CAPTURE;
}
