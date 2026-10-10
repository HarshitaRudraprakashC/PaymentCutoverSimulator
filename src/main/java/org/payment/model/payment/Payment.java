package org.payment.model.payment;

import org.payment.model.processor.ProcessorName;

import java.time.Instant;
import java.util.UUID;

public record Payment(UUID id,
                      String merchantId,
                      long amountMinor,
                      String currency,
                      ProcessorName processor,
                      PaymentStatus status,
                      int version,            // goes up on every change; used for safe updates
                      int captureAttempts,
                      Instant pendingSince,   // set when the payment enters CAPTURE_PENDING, else null
                      Instant createdAt,
                      Instant updatedAt
) {
    /** The fixed reference sent to the processor for this payment's capture. */
    public String captureReference() {
        return id + ":capture";
    }
}
