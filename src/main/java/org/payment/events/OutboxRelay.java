package org.payment.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.events.RelayFault;
import org.payment.repository.OutboxRepository;
import org.payment.repository.OutboxRepository.OutboxRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends unsent outbox rows to Kafka, then marks them sent.
 *
 * Delivery is AT LEAST ONCE: if we crash after sending but before marking, the row is still
 * unsent and goes out again next time. That's on purpose. The consumer is built for duplicates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    /** Thrown by the CRASH_BEFORE_MARK fault: "the relay died here". */
    public static class RelayCrashException extends RuntimeException {
        public RelayCrashException() {
            super("Simulated relay crash after sending, before marking sent");
        }
    }

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;

    @Value("${cutover.kafka.topic:payment-events}")
    private String topic;

    @Value("${cutover.relay.batch-size:100}")
    private int batchSize;
    private volatile RelayFault fault = RelayFault.NONE;


    public void setFault(RelayFault fault) {
        this.fault = fault;
    }

    /** Sends one batch. Returns how many events were sent and marked. */
    public int publishPending() {
        Integer sent = tx.execute(status -> {
            List<OutboxRow> batch = outboxRepository.lockUnpublished(batchSize);
            for (OutboxRow row : batch) {
                String payload = fault == RelayFault.POISON ? "{ this is not valid json" : row.payload();

                send(row.paymentId(), payload);
                if (fault == RelayFault.DUPLICATE_PUBLISH) {
                    send(row.paymentId(), payload);
                }
                if (fault == RelayFault.CRASH_BEFORE_MARK) {
                    throw new RelayCrashException();        // rolls back: nothing is marked sent
                }
                outboxRepository.markPublished(row.id());
            }
            return batch.size();
        });
        int count = sent == null ? 0 : sent;
        if (count > 0) {
            log.info("Relay sent {} event(s) to {}", count, topic);
        }
        return count;
    }

    /**
     * Key = payment id, so every event for one payment goes to the same partition, in order.
     * .get() waits for Kafka to confirm. We only mark a row sent after Kafka has it.
     */
    private void send(UUID paymentId, String payload) {
        try {
            kafka.send(topic, paymentId.toString(), payload).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending to Kafka", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not accept the event", e);
        }
    }
}