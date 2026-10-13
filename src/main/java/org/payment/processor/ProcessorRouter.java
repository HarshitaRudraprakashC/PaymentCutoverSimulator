package org.payment.processor;

import org.payment.model.processor.ProcessorName;
import org.payment.repository.RolloutRepository;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Two jobs:
 *   chooseFor(merchant)  -> which processor a NEW payment goes to (by rollout %)
 *   processorFor(name)   -> the processor an EXISTING payment is pinned to (sticky)
 */
@Component
public class ProcessorRouter {

    private final Map<ProcessorName, Processor> processors = new EnumMap<>(ProcessorName.class);
    private final RolloutRepository rollout;

    /** Spring passes in every Processor bean: OldProcessor and NewProcessor. */
    public ProcessorRouter(List<Processor> all, RolloutRepository rollout) {
        all.forEach(p -> processors.put(p.name(), p));
        this.rollout = rollout;
    }

    /** Used ONCE, at authorization. The answer is saved on the payment. */
    public ProcessorName chooseFor(String merchantId) {
        return bucketOf(merchantId) < rollout.newPercent() ? ProcessorName.NEW : ProcessorName.OLD;
    }

    /** A merchant's fixed number from 0 to 99. Same merchant -> same number, every time. */
    public static int bucketOf(String merchantId) {
        return Math.floorMod(merchantId.hashCode(), 100);
    }

    /** Used for everything after authorization: always the processor saved on the payment. */
    public Processor processorFor(ProcessorName name) {
        Processor p = processors.get(name);
        if (p == null) {
            throw new IllegalStateException("No processor bean for " + name);
        }
        return p;
    }
}