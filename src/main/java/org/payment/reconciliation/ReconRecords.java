package org.payment.reconciliation;

import org.payment.model.payment.MismatchType;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;

import java.time.Instant;
import java.util.UUID;

/** The small records reconciliation works with. Grouped in one file because each one is tiny. */
public final class ReconRecords {

    private ReconRecords() {}

    /** One of our payments, plus what our ledger says was captured for it. */
    public record OurSide(UUID paymentId, PaymentStatus status, long amountMinor, Instant pendingSince,
                          long ledgerDebit, long ledgerCredit) {
        /** Same rule as Payment.captureReference(): the reference the processor knows it by. */
        public String captureReference() {
            return paymentId + ":capture";
        }
    }

    /** A problem found during a run, before it's saved. */
    public record Finding(UUID paymentId, ProcessorName processor, MismatchType type,
                          String expected, String actual) {}

    /** A saved problem, open or resolved. */
    public record Mismatch(long id, long runId, UUID paymentId, ProcessorName processor, MismatchType type,
                           String expected, String actual, boolean resolved,
                           String resolvedBy, String resolutionNote) {}

    /** A saved run. */
    public record ReconRun(long id, Instant startedAt, Instant finishedAt, int paymentsChecked, int mismatches) {}
}
