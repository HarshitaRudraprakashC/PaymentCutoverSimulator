package org.payment.simulation;

import lombok.RequiredArgsConstructor;
import org.payment.events.PaymentEvents;
import org.payment.model.events.PaymentEventType;
import org.payment.model.exception.ApiException;
import org.payment.model.exception.ProcessorExceptions;
import org.payment.model.payment.Payment;
import org.payment.processor.Processor;
import org.payment.processor.ProcessorRouter;
import org.payment.repository.PaymentRepository;
import org.payment.service.LedgerService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * THE CONTROL GROUP. Deliberately unsafe. Never used by the API, only by the simulation.
 *
 * This is how capture is often written the first time:
 *   timeout?  "it probably failed"  -> send it again (no check first)
 *   3 timeouts in a row?            -> give up and call it failed
 *
 * On the OLD processor (no duplicate check) the blind re-send charges the customer twice.
 * Giving up after timeouts loses captures the processor actually made.
 * The simulation runs this next to the real PaymentService to show the difference in numbers.
 */
@Component
@RequiredArgsConstructor
public class NaiveCapture {

    /** What one naive capture did: did it end CAPTURED, and how many timeouts did it see. */
    public record Outcome(boolean captured, int timeouts) {}

    private static final int MAX_ATTEMPTS = 3;

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final ProcessorRouter router;
    private final PaymentEvents events;
    private final TransactionTemplate tx;


    public Outcome capture(UUID paymentId) {
        Payment authorized = get(paymentId);
        tx.executeWithoutResult(s -> paymentRepository.markCapturePending(authorized.id(), authorized.version()));
        Payment p = get(paymentId);
        Processor processor = router.processorFor(p.processor());

        int timeouts = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                processor.capture(p.captureReference(), p.amountMinor());
                tx.executeWithoutResult(s -> {
                    if (paymentRepository.markCaptured(p.id(), p.version())) {
                        ledgerService.postCapture(p);
                        events.record(PaymentEventType.PAYMENT_CAPTURED, p.id());
                    }
                });
                return new Outcome(true, timeouts);
            } catch (ProcessorExceptions.ProcessorTimeoutException e) {
                timeouts++;                     // THE BUG: treat "no answer" as "didn't happen" and resend
            }
        }
        // THE OTHER BUG: after 3 timeouts, call it failed. The processor may well have captured.
        tx.executeWithoutResult(s -> paymentRepository.markCaptureFailed(p.id(), p.version()));
        return new Outcome(false, timeouts);
    }

    private Payment get(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new ApiException.PaymentNotFoundException(id));
    }
}