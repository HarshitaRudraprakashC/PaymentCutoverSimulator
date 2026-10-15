package org.payment.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.payment.model.events.PaymentEvent;
import org.payment.model.events.PaymentEventType;
import org.payment.model.exception.ApiException.PaymentNotFoundException;
import org.payment.model.payment.Payment;
import org.payment.repository.OutboxRepository;
import org.payment.repository.PaymentRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes "this just happened" to the outbox. Call it INSIDE the transaction that made the change,
 * right after the guarded update won: the event is saved if and only if the change is saved.
 */
@Component
@RequiredArgsConstructor
public class PaymentEvents {

    private final PaymentRepository paymentRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper json;

    public void record(PaymentEventType type, UUID paymentId) {
        // Read the payment again inside the same transaction, so the event carries the NEW version.
        Payment payment = paymentRepository.findById(paymentId).orElseThrow(() -> new PaymentNotFoundException(paymentId));
        PaymentEvent event = new PaymentEvent(UUID.randomUUID(), type, payment.id(), payment.version(),
                payment.merchantId(), payment.amountMinor(), payment.currency(), Instant.now());
        outboxRepository.append(event, toJson(event));
    }

    private String toJson(PaymentEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write event " + event.eventId(), e);
        }
    }
}