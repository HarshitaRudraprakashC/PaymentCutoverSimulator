package org.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.exception.ApiException;
import org.payment.model.exception.ApiException.InvalidStateException;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.request.AuthRequest;
import org.payment.processor.Processor;
import org.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledger;
    private final Processor processor;
    private final TransactionTemplate tx;


    public Payment authorize(AuthRequest request, String idempotencyKey) {
        Payment payment = new Payment(
                UUID.randomUUID(), request.merchantId(), request.amount(), request.currency(),
                processor.name(), PaymentStatus.AUTHORIZED,
                0, 0, null, null, null);
        paymentRepository.insert(payment);
        return get(payment.id());
    }

    public Payment capture(UUID paymentId, String idempotencyKey) {
        Payment pending = tx.execute(status -> {
            Payment p = get(paymentId);
            if (p.status() != PaymentStatus.AUTHORIZED) {
                throw new InvalidStateException("Payment " + paymentId + " is " + p.status() + ", expected AUTHORIZED");
            }
            if (!paymentRepository.markCapturePending(p.id(), p.version())) {
                throw new InvalidStateException("Payment " + paymentId + " was changed by another request; retry");
            }
            return get(paymentId);
        });

        // No transaction open: call the processor.
        processor.capture(pending.captureReference(), pending.amountMinor());

        // Transaction 2: status change and ledger entries succeed or fail TOGETHER.
        return tx.execute(status -> {
            if (!paymentRepository.markCaptured(pending.id(), pending.version())) {
                throw new InvalidStateException("Payment " + paymentId + " changed while capturing");
            }
            ledger.postCapture(pending);
            return get(paymentId);
        });
    }

    public Payment get(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ApiException.PaymentNotFoundException(paymentId));
    }
}
