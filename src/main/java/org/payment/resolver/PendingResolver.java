package org.payment.resolver;


import lombok.RequiredArgsConstructor;
import org.payment.model.payment.Payment;
import org.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Finds payments stuck in CAPTURE_PENDING and resolves them. This is what guarantees
 * progress when a client never retries (it crashed, gave up, or the user closed the page).
 */
@Component
@RequiredArgsConstructor
public class PendingResolver {

    private static final Logger log = LoggerFactory.getLogger(PendingResolver.class);

    private final PaymentRepository paymentRepository;
    private final CaptureResolver resolver;

    /**
     * Resolve payments that have been pending for longer than minAgeSeconds.
     *
     * Why wait at all? A payment that JUST entered CAPTURE_PENDING may still have its first
     * capture call in flight. If we asked now we'd hear "never saw it", send again, and then
     * the first call would land too: a double charge on the old processor. Waiting longer
     * than any processor call can take avoids that.
     */
    public int resolveOlderThan(long minAgeSeconds) {
        List<UUID> stuck = paymentRepository.findPendingOlderThan(minAgeSeconds, 100);
        for (UUID id : stuck) {
            try {
                Payment after = resolver.resolve(id);
                log.info("Resolved pending payment {} -> {}", id, after.status());
            } catch (RuntimeException e) {
                // One bad payment must not stop the rest of the batch. It stays pending,
                // and reconciliation flags it if it's stuck for too long.
                log.warn("Could not resolve payment {}: {}", id, e.getMessage());
            }
        }
        return stuck.size();
    }
}
