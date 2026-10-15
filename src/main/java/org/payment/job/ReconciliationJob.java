package org.payment.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.payment.MismatchType;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;
import org.payment.processor.ProcessorReportLine;
import org.payment.processor.ProcessorRouter;
import org.payment.reconciliation.ReconRecords.Finding;
import org.payment.reconciliation.ReconRecords.OurSide;
import org.payment.repository.ReconRepository;
import org.payment.repository.ReconRepository.DriftRow;
import org.payment.resolver.CaptureResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.payment.model.payment.MismatchType.MISSING_INTERNAL;
import static org.payment.model.payment.MismatchType.PROJECTION_DRIFT;

/**
 * Reconciliation: compare what WE think happened with what each PROCESSOR says happened,
 * payment by payment, and record every difference.
 * <p>
 * It only fixes one kind of problem by itself: a payment stuck in CAPTURE_PENDING that the
 * processor DID capture (we crashed before saving). That fix is safe because it goes through
 * the same CaptureResolver as every other recovery path. Everything else is recorded for a
 * person to look at. Reconciliation never changes amounts or deletes anything.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationJob {

    private final ReconRepository reconRepository;
    private final ProcessorRouter router;
    private final CaptureResolver resolver;

    @Value("${cutover.recon.projection-grace-seconds:30}")
    private long projectionGraceSeconds;

    @Value("${cutover.recon.pending-grace-seconds:30}")
    private long pendingGraceSeconds;


    /**
     * Runs a full comparison of both processors. Returns the run id.
     */
    public long run() {
        long runId = reconRepository.startRun();
        int checked = 0;
        int found = 0;

        for (ProcessorName name : ProcessorName.values()) {
            Map<String, ProcessorReportLine> report = router.processorFor(name).report().stream()
                    .collect(Collectors.toMap(ProcessorReportLine::reference, Function.identity()));

            for (OurSide ours : reconRepository.ourSide(name)) {
                checked++;
                ProcessorReportLine theirs = report.get(ours.captureReference());
                for (Finding f : compare(name, ours, theirs)) {
                    found++;
                    if (f.type() == MISSING_INTERNAL && ours.status() == PaymentStatus.CAPTURE_PENDING) {
                        finishStuckCapture(runId, f);           // S7: fix it, and keep a record
                    } else {
                        reconRepository.saveOpen(runId, f);
                    }
                }
            }
        }

        for (DriftRow driftRow : reconRepository.findProjectionDrift(projectionGraceSeconds)) {
            found++;
            reconRepository.saveOpen(runId, new Finding(driftRow.paymentId(), driftRow.processor(), PROJECTION_DRIFT,
                    driftRow.status().name(), driftRow.projectedStatus() == null ? "missing" : driftRow.projectedStatus()));
        }

        reconRepository.finishRun(runId, checked, found);
        log.info("Reconciliation run {}: {} payments checked, {} problem(s) found", runId, checked, found);
        return runId;
    }

    /**
     * The rules. Given our record and the processor's line for one payment, list what's wrong.
     * An empty list means both sides agree.
     */
    List<Finding> compare(ProcessorName processor, OurSide ours, ProcessorReportLine theirs) {
        int captures = theirs == null ? 0 : theirs.captures();
        long capturedMinor = theirs == null ? 0 : theirs.amountMinor();
        String expected = "1 capture of " + ours.amountMinor();
        String actual = captures + " capture(s), total " + capturedMinor;
        List<Finding> findings = new ArrayList<>();

        switch (ours.status()) {
            case CAPTURED, SETTLED -> {
                // We say the money moved. The processor must have exactly one capture of our amount.
                if (captures == 0) {
                    findings.add(new Finding(ours.paymentId(), processor, MismatchType.MISSING_AT_PROCESSOR, expected, actual));
                } else if (captures > 1) {
                    findings.add(new Finding(ours.paymentId(), processor, MismatchType.DUPLICATE_CAPTURE, expected, actual));
                } else if (capturedMinor != ours.amountMinor()) {
                    findings.add(new Finding(ours.paymentId(), processor, MismatchType.AMOUNT_MISMATCH,
                            String.valueOf(ours.amountMinor()), String.valueOf(capturedMinor)));
                }
                // And our own ledger must say the same amount, on both sides.
                if (ours.ledgerDebit() != ours.amountMinor() || ours.ledgerCredit() != ours.amountMinor()) {
                    findings.add(new Finding(ours.paymentId(), processor, MismatchType.LEDGER_MISMATCH,
                            "debit " + ours.amountMinor() + " / credit " + ours.amountMinor(),
                            "debit " + ours.ledgerDebit() + " / credit " + ours.ledgerCredit()));
                }
            }
            case AUTHORIZED, FAILED -> {
                // We say no money moved. The processor must have nothing.
                if (captures > 0) {
                    findings.add(new Finding(ours.paymentId(), processor, MISSING_INTERNAL, "no capture", actual));
                }
            }
            case CAPTURE_PENDING -> {
                // We don't know yet. Only a problem if the processor has it AND it has been
                // pending longer than the grace period (so we don't race a live request).
                if (captures > 0 && pendingLongerThanGrace(ours)) {
                    findings.add(new Finding(ours.paymentId(), processor, MISSING_INTERNAL, "CAPTURED", "CAPTURE_PENDING"));
                }
                if (captures > 1) {
                    findings.add(new Finding(ours.paymentId(), processor, MismatchType.DUPLICATE_CAPTURE, expected, actual));
                }
            }
        }
        return findings;
    }

    /**
     * S7: the processor captured, but we crashed before saving CAPTURED.
     * Finish it with the normal resolution rule, then keep a record that it happened.
     */
    private void finishStuckCapture(long runId, Finding f) {
        Payment after = resolver.resolve(f.paymentId());
        if (after.status() == PaymentStatus.CAPTURED) {
            reconRepository.saveAutoResolved(runId, f, "reconciliation", "Processor had the capture; finished by CaptureResolver");
        } else {
            reconRepository.saveOpen(runId, f);                   // couldn't finish it: a person must look
        }
    }

    private boolean pendingLongerThanGrace(OurSide ours) {
        return ours.pendingSince() != null
                && ours.pendingSince().isBefore(Instant.now().minusSeconds(pendingGraceSeconds));
    }
}
