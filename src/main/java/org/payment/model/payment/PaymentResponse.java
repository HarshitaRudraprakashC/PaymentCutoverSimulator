package org.payment.model.payment;

import org.payment.model.processor.ProcessorName;

import java.time.Instant;
import java.util.UUID;

/**
 * What every payment endpoint returns.
 * <p>
 * {
 * "id": "0b6f...",
 * "merchantId": "m-123",
 * "amount": 10000,
 * "currency": "EUR",
 * "processor": "NEW",
 * "status": "CAPTURE_PENDING",
 * "captureAttempts": 1,
 * "createdAt": "2026-10-08T06:00:00Z",
 * "updatedAt": "2026-10-08T06:00:01Z"
 * }
 * <p>
 * Kept separate from Payment on purpose: internal fields like `version`
 * never leak out, and the database can change without breaking clients.
 */
public record PaymentResponse(
        UUID id,
        String merchantId,
        long amount,
        String currency,
        ProcessorName processor,
        PaymentStatus status,
        int captureAttempts,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.id(), p.merchantId(), p.amountMinor(), p.currency(),
                p.processor(), p.status(), p.captureAttempts(),
                p.createdAt(), p.updatedAt());
    }
}
