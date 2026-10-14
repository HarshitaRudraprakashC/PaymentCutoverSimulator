package org.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.migration.RolloutGate;
import org.payment.model.exception.ApiException.GateBlockedException;
import org.payment.repository.RolloutRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The only way to change the rollout from the API: every change goes through the gate.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RolloutService {

    private final RolloutRepository rollout;
    private final RolloutGate gate;

    /**
     * Move to a new percentage, or throw GateBlockedException (409) saying exactly why not.
     */
    public int moveTo(int targetPercent) {
        int current = rollout.newPercent();
        List<String> reasons = gate.reasonsToBlock(current, targetPercent);
        if (!reasons.isEmpty()) {
            log.warn("Rollout {}% -> {}% BLOCKED: {}", current, targetPercent, reasons);
            throw new GateBlockedException("Can't move from " + current + "% to " + targetPercent + "%: "
                    + String.join("; ", reasons));
        }
        if (!rollout.compareAndSet(current, targetPercent)) {
            throw new GateBlockedException("The rollout was changed by someone else; check it and try again");
        }
        log.info("Rollout {}% -> {}%", current, targetPercent);
        return targetPercent;
    }
}
