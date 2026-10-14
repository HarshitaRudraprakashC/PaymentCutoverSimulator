package org.payment.migration;

import lombok.RequiredArgsConstructor;
import org.payment.model.processor.ProcessorName;
import org.payment.repository.PaymentRepository;
import org.payment.repository.ReconRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ADR-0004: the checks that must pass before more merchants move to the NEW processor.
 *
 *   going UP   -> one step at a time, and only with evidence that NEW is healthy
 *   going DOWN -> always allowed (rollback must never wait for anything)
 */
@Component
@RequiredArgsConstructor
public class RolloutGate {

    public static final List<Integer> STEPS = List.of(0, 5, 25, 50, 100);

    private final ReconRepository reconRepository;
    private final PaymentRepository paymentsRepository;

    @Value("${cutover.gate.max-recon-age-minutes:60}")
    private long maxReconAgeMinutes;

    @Value("${cutover.gate.max-failure-rate:0.01}")
    private double maxFailureRate;

    @Value("${cutover.gate.stuck-pending-seconds:300}")
    private long stuckPendingSeconds;

    /** Every reason the move is blocked. An empty list means the move is allowed. */
    public List<String> reasonsToBlock(int fromPercent, int toPercent) {
        if (!STEPS.contains(toPercent)) {
            return List.of("Rollout must be one of " + STEPS);
        }
        if (toPercent <= fromPercent) {
            return List.of();
        }

        List<String> reasons = new ArrayList<>();
        int next = nextStep(fromPercent);
        if (toPercent != next) {
            reasons.add("One step at a time: from " + fromPercent + "% the next step is " + next + "%");
        }
        if (!reconRepository.finishedWithinMinutes(maxReconAgeMinutes)) {
            reasons.add("Reconciliation hasn't run in the last " + maxReconAgeMinutes + " minutes");
        }
        int open = reconRepository.countOpen(ProcessorName.NEW);
        if (open > 0) {
            reasons.add(open + " unresolved reconciliation problem(s) on NEW");
        }
        double failureRate = paymentsRepository.captureFailureRate(ProcessorName.NEW);
        if (failureRate >= maxFailureRate) {
            reasons.add(String.format("NEW capture failure rate is %.2f%% (limit %.2f%%)",
                    failureRate * 100, maxFailureRate * 100));
        }
        int stuck = paymentsRepository.countStuckPending(ProcessorName.NEW, stuckPendingSeconds);
        if (stuck > 0) {
            reasons.add(stuck + " payment(s) stuck in CAPTURE_PENDING on NEW for over " + stuckPendingSeconds + "s");
        }
        return reasons;
    }

    /** The next step up from any percentage, e.g. 5 -> 25, 7 -> 25, 100 -> 100. */
    static int nextStep(int fromPercent) {
        return STEPS.stream().filter(s -> s > fromPercent).findFirst().orElse(100);
    }
}