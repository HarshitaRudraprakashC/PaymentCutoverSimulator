package org.payment.model.events;

public enum RelayFault {
    NONE,
    DUPLICATE_PUBLISH,
    CRASH_BEFORE_MARK,
    POISON;

}
