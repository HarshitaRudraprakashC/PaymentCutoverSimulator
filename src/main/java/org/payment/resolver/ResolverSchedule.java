package org.payment.resolver;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the PendingResolver every few seconds while the app is running.
 * Switched off in tests (cutover.resolver.enabled=false) so tests decide exactly when it runs.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "cutover.resolver.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ResolverSchedule {

    private final PendingResolver pendingResolver;

    @Value("${cutover.resolver.min-pending-seconds:30}")
    private long minAgeSeconds;

    @Scheduled(fixedDelayString = "${cutover.resolver.interval-ms:5000}")
    public void run() {
        pendingResolver.resolveOlderThan(minAgeSeconds);
    }
}
