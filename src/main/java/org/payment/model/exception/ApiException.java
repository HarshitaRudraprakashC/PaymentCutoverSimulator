package org.payment.model.exception;

import java.util.UUID;

public final class ApiException {

    private ApiException() {}

    /** 404: no payment with this id. */
    public static class PaymentNotFoundException extends RuntimeException {
        public PaymentNotFoundException(UUID id) {
            super("Payment " + id + " not found");
        }
    }

    /** 409: a request with this key is still being processed. Client should retry shortly. */
    public static class IdempotencyInProgressException extends RuntimeException {
        public IdempotencyInProgressException(String key) {
            super("A request with key " + key + " is still in progress");
        }
    }

    /** 422: the key was already used for a different request (different body or path). */
    public static class IdempotencyKeyReusedException extends RuntimeException {
        public IdempotencyKeyReusedException(String key) {
            super("Key " + key + " was already used for a different request");
        }
    }

    /** 409: the payment can't do this from its current state, e.g. capturing a FAILED payment. */
    public static class InvalidStateException extends RuntimeException {
        public InvalidStateException(String message) {
            super(message);
        }
    }

    /** 409: the rollout gate said no. The message says which check failed. */
    public static class GateBlockedException extends RuntimeException {
        public GateBlockedException(String reason) {
            super(reason);
        }
    }
}
