package org.payment.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.events.PaymentEvent;
import org.payment.repository.ProjectionRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds each merchant's balance from payment events.
 *
 * For every message, in ONE database transaction:
 *   seen this event id before?          -> skip (duplicate)
 *   older than what we already applied? -> skip (stale)
 *   otherwise                           -> store the payment's new state
 *
 * Kafka is told "done" (the offset is committed) only after this method returns, so after
 * the transaction committed. If we crash before that, Kafka sends it again, and the
 * duplicate check makes that harmless.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MerchantBalanceConsumer {

    private final ProjectionRepository projectionRepository;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    private final AtomicInteger applied = new AtomicInteger();
    private final AtomicInteger duplicates = new AtomicInteger();
    private final AtomicInteger stale = new AtomicInteger();

    @KafkaListener(topics = "${cutover.kafka.topic:payment-events}", groupId = "merchant-balances")
    public void onMessage(String message) throws JsonProcessingException {
        // A broken message throws here. The error handler retries it, then sends it to the DLT.
        PaymentEvent event = json.readValue(message, PaymentEvent.class);

        tx.executeWithoutResult(status -> {
            if (!projectionRepository.markProcessed(event.eventId())) {
                duplicates.incrementAndGet();
                log.info("Duplicate event {} skipped", event.eventId());
                return;
            }
            if (!projectionRepository.apply(event)) {
                stale.incrementAndGet();
                log.info("Stale event {} (payment {} v{}) skipped", event.eventId(), event.paymentId(), event.paymentVersion());
                return;
            }
            applied.incrementAndGet();
        });
    }

    public int appliedCount() { return applied.get(); }
    public int duplicateCount() { return duplicates.get(); }
    public int staleCount() { return stale.get(); }
}
