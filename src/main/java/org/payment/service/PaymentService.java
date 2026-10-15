package org.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.events.PaymentEvents;
import org.payment.model.events.PaymentEvent;
import org.payment.model.events.PaymentEventType;
import org.payment.model.exception.ApiException;
import org.payment.model.exception.ApiException.InvalidStateException;
import org.payment.model.exception.ProcessorExceptions;
import org.payment.model.exception.ProcessorExceptions.ProcessorFailedException;
import org.payment.model.idempotency.RequestHash;
import org.payment.model.idempotency.StoredResponse;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentResponse;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;
import org.payment.model.request.AuthRequest;
import org.payment.processor.ProcessorRouter;
import org.payment.repository.PaymentRepository;
import org.payment.resolver.CaptureResolver;
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
    private final TransactionTemplate tx;
    private final IdempotencyService idempotencyService;
    private final ProcessorRouter router;
    private final CaptureResolver captureResolver;
    private final PaymentEvents paymentEvents;


    public StoredResponse authorize(String idempotencyKey, AuthRequest request) {
        String merchantId = request.merchantId();
        long amount = request.amount();
        String currency = request.currency();

        String hash = RequestHash.of("POST", "/payments" + merchantId, amount, currency);

        return tx.execute(status -> {
            Optional<StoredResponse> replay = idempotencyService.begin(request.merchantId(), idempotencyKey, hash);
            if (replay.isPresent()) {
                return replay.get();
            }

            ProcessorName processor = router.chooseFor(request.merchantId());
            Payment payment = new Payment(
                    UUID.randomUUID(), request.merchantId(), request.amount(), request.currency(),
                    processor, PaymentStatus.AUTHORIZED,
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
            if (first.replay().httpStatus() != 202) {
                return first.replay();
            }
            return resolveAndAnswer(paymentId, idempotencyKey);
        }
        Payment pending = first.pending();

        // No transaction open.// capture: between transaction 1 and transaction 2
        try {
            router.processorFor(pending.processor())
                    .capture(pending.captureReference(), pending.amountMinor());

        } catch (ProcessorFailedException e) {
            // We KNOW nothing was captured: undo, and free the key so a retry can run again.
            tx.executeWithoutResult(status -> {
                if (!paymentRepository.markCaptureFailed(pending.id(), pending.version())) {
                    throw new InvalidStateException("Payment " + paymentId + " changed while capturing");
                }
                idempotencyService.release(pending.merchantId(), idempotencyKey);
            });
            throw e;

        } catch (ProcessorExceptions.ProcessorTimeoutException e) {
            // We DON'T KNOW. Money may have moved. Stay CAPTURE_PENDING and say so: 202.
            // Never retry here, never mark failed. Step 5 finds out what really happened.
            return tx.execute(status -> {
                StoredResponse response = new StoredResponse(202, PaymentResponse.from(get(paymentId)));
                idempotencyService.complete(pending.merchantId(), idempotencyKey, paymentId, response);
                return response;
            });
        }

        // Transaction 2: status, ledger AND the saved answer, all together.
        return tx.execute(status -> {
            if (!paymentRepository.markCaptured(pending.id(), pending.version())) {
                throw new InvalidStateException("Payment " + paymentId + " changed while capturing");
            }
            ledger.postCapture(pending);
            paymentEvents.record(PaymentEventType.PAYMENT_CAPTURED, pending.id());
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

    private StoredResponse resolveAndAnswer(UUID paymentId, String idempotencyKey) {
        Payment now = captureResolver.resolve(paymentId);

        if (now.status() == PaymentStatus.AUTHORIZED) {
            // The re-send definitely failed: free the key, answer 502.
            tx.executeWithoutResult(s -> idempotencyService.release(now.merchantId(), idempotencyKey));
            throw new ProcessorFailedException(now.processor());
        }
        int httpStatus = now.status() == PaymentStatus.CAPTURE_PENDING ? 202 : 200;
        StoredResponse answer = new StoredResponse(httpStatus, PaymentResponse.from(now));
        tx.executeWithoutResult(s -> idempotencyService.complete(now.merchantId(), idempotencyKey, paymentId, answer));
        return answer;
    }
}
