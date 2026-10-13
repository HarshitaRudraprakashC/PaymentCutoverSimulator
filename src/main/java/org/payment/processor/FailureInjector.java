package org.payment.processor;


import org.payment.model.exception.ProcessorExceptions;
import org.payment.model.exception.ProcessorExceptions.ProcessorFailedException;
import org.payment.model.processor.ProcessorName;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Makes the fake processors misbehave on command. One mode per processor at a time,
 * firing on a fraction of calls (rate 1.0 = every call, 0.05 = 5% of calls).
 */
@Component
public class FailureInjector {

    public record Config(FailureMode mode, double rate) {}

    private final Map<ProcessorName, Config> configs = new ConcurrentHashMap<>();

    public void set(ProcessorName processor, FailureMode mode, double rate) {
        configs.put(processor, new Config(mode, rate));
    }

    public void reset() {
        configs.clear();
    }

    public Config current(ProcessorName processor) {
        return configs.getOrDefault(processor, new Config(FailureMode.NONE, 0));
    }

    /** Called before the processor records anything. */
    public void beforeCapture(ProcessorName p) {
        if (fires(p, FailureMode.FAIL_BEFORE)) {
            throw new ProcessorFailedException(p);
        }
        if (fires(p, FailureMode.LATENCY)) {
            sleep(2000);
        }
    }

    /** Lets the planted bug change the amount actually captured. */
    public long amountToCapture(ProcessorName p, long requestedMinor) {
        return fires(p, FailureMode.UNDER_CAPTURE) ? requestedMinor - 1 : requestedMinor;
    }

    /** Called AFTER the processor has recorded the capture: the money has moved. */
    public void afterCapture(ProcessorName p) {
        if (fires(p, FailureMode.TIMEOUT_AFTER_SUCCESS)) {
            throw new ProcessorExceptions.ProcessorTimeoutException(p);
        }
        if (fires(p, FailureMode.CRASH_AFTER_SUCCESS)) {
            throw new ProcessorExceptions.SimulatedCrashException(p);
        }
    }

    private boolean fires(ProcessorName p, FailureMode mode) {
        Config c = configs.get(p);
        return c != null && c.mode() == mode && ThreadLocalRandom.current().nextDouble() < c.rate();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
