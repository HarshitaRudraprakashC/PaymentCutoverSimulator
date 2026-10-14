package org.payment.resolver;

import lombok.RequiredArgsConstructor;
import org.payment.model.exception.ApiException;
import org.payment.model.exception.ApiException.PaymentNotFoundException;
import org.payment.model.exception.ProcessorExceptions;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorStatus;
import org.payment.processor.Processor;
import org.payment.processor.ProcessorRouter;
import org.payment.repository.PaymentRepository;
import org.payment.service.LedgerService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * The resolution rule (ADR-0002). Every recovery path uses this one method.
 */
@Component
@RequiredArgsConstructor
public class CaptureResolver {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledger;
    private final ProcessorRouter router;
    private final TransactionTemplate tx;


    /**
     * Returns the payment as it stands after trying to resolve it. Safe to call any number of times.
     */
    public Payment resolve(UUID paymentId) {
        Payment payment = get(paymentId);
        if (!payment.status().equals(PaymentStatus.CAPTURE_PENDING)) {
            return payment;
        }
        Processor processor = router.processorFor(payment.processor());

        ProcessorStatus status;
        try {
            status = processor.getStatus(payment.captureReference());
        } catch (ProcessorExceptions.ProcessorTimeoutException e) {
            return payment;
        }

        if (status.captured()) {
            finishCaptured(payment);
            return get(paymentId);
        }
        if (!paymentRepository.markResend(payment.id(), payment.version())) {
            return get(paymentId);
        }
        Payment again = get(paymentId);
        try {
            processor.capture(again.captureReference(), again.amountMinor());
        } catch (ProcessorExceptions.ProcessorFailedException e) {
            tx.executeWithoutResult(s -> paymentRepository.markCaptureFailed(again.id(), again.version()));
            return get(paymentId);
        }
        finishCaptured(again);
        return get(paymentId);
    }

    /**
     * CAPTURED + ledger in one transaction. If markCaptured returns false, someone else
     * finished it first, so we post nothing: the ledger can never get two capture journals.
     */
    private void finishCaptured(Payment p) {
        tx.executeWithoutResult(s -> {
            if (paymentRepository.markCaptured(p.id(), p.version())) {
                ledger.postCapture(p);
            }
        });
    }

    private Payment get(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }
}
