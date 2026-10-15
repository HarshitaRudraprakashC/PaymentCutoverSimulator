package org.payment.model.events;

import java.time.Instant;
import java.util.UUID;

/**
 * What goes on the Kafka topic, as JSON.
 *
 * eventId        -> lets the consumer spot a duplicate (the same event delivered twice)
 * paymentVersion -> lets the consumer spot an old event arriving after a newer one
 */
public record PaymentEvent(
        UUID eventId,
        PaymentEventType type,
        UUID paymentId,
        int paymentVersion,
        String merchantId,
        long amountMinor,
        String currency,
        Instant occurredAt
) {}
