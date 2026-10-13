package org.payment.model.exception;

import org.payment.model.processor.ProcessorName;

/**
 * What can go wrong when calling a processor. The difference between the first two is
 * the whole point of the project:
 *   ProcessorFailedException  -> we KNOW nothing was captured. Safe to undo and retry.
 *   ProcessorTimeoutException -> we DON'T KNOW. Money may have moved. Never assume either way.
 */
public final class ProcessorExceptions {

    private ProcessorExceptions() {}

    public static class ProcessorFailedException extends RuntimeException {
        public ProcessorFailedException(ProcessorName processor) {
            super(processor + " processor failed before capturing (nothing was charged)");
        }
    }

    public static class ProcessorTimeoutException extends RuntimeException {
        public ProcessorTimeoutException(ProcessorName processor) {
            super(processor + " processor timed out: outcome unknown");
        }
    }

    /** Simulates OUR service dying right after the processor captured. Used in step 5/6 tests. */
    public static class SimulatedCrashException extends RuntimeException {
        public SimulatedCrashException(ProcessorName processor) {
            super("Simulated crash after " + processor + " captured");
        }
    }
}
