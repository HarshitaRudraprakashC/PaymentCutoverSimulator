package org.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.exception.ApiException;
import org.payment.model.exception.ApiException.InvalidStateException;
import org.payment.model.idempotency.RequestHash;
import org.payment.model.idempotency.StoredResponse;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentResponse;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.request.AuthRequest;
import org.payment.processor.Processor;
import org.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final LedgerService ledger;
    private final Processor processor;
    private final TransactionTemplate tx;
    private final IdempotencyService idempotencyService;


    public StoredResponse authorize(String idempotencyKey, AuthRequest request) {
        String merchantId = request.merchantId();
        Integer amount = request.amount();
        String currency = request.currency();

        String hash = RequestHash.of("POST", "/payments" + merchantId, amount, currency);

        return tx.execute(status -> {
            Optional<StoredResponse> replay = idempotencyService.begin(request.merchantId(), idempotencyKey, hash);
            if (replay.isPresent()) {
                return replay.get();
            }

            Payment payment = new Payment(
                    UUID.randomUUID(), merchantId, amount, currency, processor.name(), PaymentStatus.AUTHORIZED,
                    0, 0, null, null, null);
            paymentRepository.insert(payment);

            StoredResponse response = new StoredResponse(201, PaymentResponse.from(get(payment.id())));
            idempotencyService.complete(merchantId, idempotencyKey, payment.id(), response);
            return response;
        });
    }

    public StoredResponse capture(UUID paymentId, String idempotencyKey) {
        String hash = RequestHash.of("POST", "/payments/" + paymentId + "/capture");

        TxOne first = tx.execute(status -> {
            Payment p = get(paymentId);
            Optional<StoredResponse> previous = idempotencyService.begin(p.merchantId(), idempotencyKey, hash);

            if (previous.isPresent()) {
                return TxOne.replay(previous.get());
            }
            if (p.status() != PaymentStatus.AUTHORIZED) {
                throw new InvalidStateException("Payment " + paymentId + " is " + p.status() + ", expected AUTHORIZED");
            }
            if (!paymentRepository.markCapturePending(p.id(), p.version())) {
                throw new InvalidStateException("Payment " + paymentId + " was changed by another request; retry");
            }
            return TxOne.proceed(get(paymentId));
        });
        if (first.replay() != null) {
            return first.replay();
        }
        Payment pending = first.pending();

        // No transaction open.
        processor.capture(pending.captureReference(), pending.amountMinor());

        // Transaction 2: status, ledger AND the saved answer, all together.
        return tx.execute(status -> {
            if (!paymentRepository.markCaptured(pending.id(), pending.version())) {
                throw new InvalidStateException("Payment " + paymentId + " changed while capturing");
            }
            ledger.postCapture(pending);
            StoredResponse response = new StoredResponse(200, PaymentResponse.from(get(paymentId)));
            idempotencyService.complete(pending.merchantId(), idempotencyKey, paymentId, response);
            return response;
        });
}

/**
 * What transaction 1 of capture hands back: a replayed answer, OR the pending payment.
 */
private record TxOne(StoredResponse replay, Payment pending) {
    static TxOne replay(StoredResponse r) {
        return new TxOne(r, null);
    }

    static TxOne proceed(Payment p) {
        return new TxOne(null, p);
    }
}

public Payment get(UUID paymentId) {
    return paymentRepository.findById(paymentId)
            .orElseThrow(() -> new ApiException.PaymentNotFoundException(paymentId));
}
}
