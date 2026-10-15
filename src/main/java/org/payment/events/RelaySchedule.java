package org.payment.events;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay every half second while the app is running.
 * Switched off in tests (cutover.relay.enabled=false), so tests decide exactly when events go out.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "cutover.relay.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RelaySchedule {

    private final OutboxRelay relay;

    @Scheduled(fixedDelayString = "${cutover.relay.interval-ms:500}")
    public void run() {
        try {
            relay.publishPending();
        } catch (RuntimeException e) {
            // Nothing was marked sent, so the same events are tried again next time.
            log.warn("Relay batch failed, will retry: {}", e.getMessage());
        }
    }
}
