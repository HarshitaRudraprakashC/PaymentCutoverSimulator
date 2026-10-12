package org.payment.model.idempotency;

import org.payment.model.payment.PaymentResponse;

/** What we answered: the HTTP status and the body. Saved with the key, replayed for a retry. */
public record StoredResponse(int httpStatus, PaymentResponse body) {}
