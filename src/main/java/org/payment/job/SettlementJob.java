package org.payment.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;
import org.payment.processor.ProcessorReportLine;
import org.payment.processor.ProcessorRouter;
import org.payment.repository.PaymentRepository;
import org.payment.service.LedgerService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Settlement: the processor pays out the money it captured, and we mark those payments SETTLED.
 *
 * The one rule: settle ONLY what matches exactly (one capture, same amount).
 * Anything else is left as CAPTURED. Reconciliation reports why, and a person decides.
 * Settling a payment we can't explain would hide the problem.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementJob {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final ProcessorRouter router;
    private final TransactionTemplate tx;


    /** Settle both processors. Returns how many payments were settled. */
    public int settleAll() {
        int settled = 0;
        for (ProcessorName name : ProcessorName.values()) {
            settled += settle(name);
        }
        return settled;
    }

    public int settle(ProcessorName name) {
        Map<String, ProcessorReportLine> report = router.processorFor(name).report().stream()
                .collect(Collectors.toMap(ProcessorReportLine::reference, Function.identity()));

        int settled = 0;
        for (Payment p : paymentRepository.findByProcessorAndStatus(name, PaymentStatus.CAPTURED)) {
            ProcessorReportLine line = report.get(p.captureReference());
            if (line == null || line.captures() != 1 || line.amountMinor() != p.amountMinor()) {
                continue;                       // doesn't match: leave it for reconciliation
            }
            // SETTLED + ledger in one transaction. If markSettled loses (someone else settled it
            // first), post nothing: the ledger can never get two settlement journals.
            Boolean done = tx.execute(status -> {
                if (!paymentRepository.markSettled(p.id(), p.version())) {
                    return false;
                }
                ledgerService.postSettlement(p);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                settled++;
            }
        }
        log.info("Settlement on {}: {} payment(s) settled", name, settled);
        return settled;
    }
}