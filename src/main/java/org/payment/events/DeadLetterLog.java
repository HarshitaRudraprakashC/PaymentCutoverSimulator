package org.payment.events;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads the dead-letter topic so broken messages are visible, not silently parked.
 * A real system would alert someone here. We keep them in memory for the tests and the admin API.
 */
@Component
@Slf4j
public class DeadLetterLog {

    private final List<String> received = new CopyOnWriteArrayList<>();

    @KafkaListener(topics = "${cutover.kafka.topic:payment-events}.DLT", groupId = "dead-letter-log")
    public void onDeadLetter(String message) {
        log.error("Dead letter received: {}", message);
        received.add(message);
    }

    public List<String> received() {
        return List.copyOf(received);
    }
}
