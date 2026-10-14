package org.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.payment.model.exception.ApiException;
import org.payment.model.exception.ProcessorExceptions;
import org.payment.model.idempotency.StoredResponse;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;
import org.payment.model.request.AuthRequest;
import org.payment.processor.FailureInjector;
import org.payment.processor.FailureMode;
import org.payment.processor.OldProcessor;
import org.payment.repository.PaymentRepository;
import org.payment.repository.RolloutRepository;
import org.payment.resolver.CaptureResolver;
import org.payment.resolver.PendingResolver;
import org.payment.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolverTest extends IntegrationTestBase {

    @Autowired
    PaymentService paymentService;
    @Autowired
    PaymentRepository paymentRepository;
    @Autowired
    CaptureResolver captureResolver;
    @Autowired
    PendingResolver pendingResolver;
    @Autowired FailureInjector failures;
    @Autowired
    RolloutRepository rollout;
    @Autowired OldProcessor oldProcessor;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void resetSwitches() {
        failures.reset();
        rollout.setNewPercent(0);
    }

    /** S2: the killer demo. Timeout after success, then the client retries with the same key. */
    @Test
    void timeoutThenRetrySameKey_capturesOnce() {
        UUID id = authorize(10_000);                       // rollout 0 -> OLD
        String key = "cap-" + UUID.randomUUID();
        failures.set(ProcessorName.OLD, FailureMode.TIMEOUT_AFTER_SUCCESS, 1.0);

        StoredResponse first = paymentService.capture(id, key);
        assertThat(first.httpStatus()).isEqualTo(202);
        failures.reset();
        StoredResponse retry = paymentService.capture(id, key);
        assertThat(retry.httpStatus()).isEqualTo(200);
        assertThat(retry.body().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(1);
        assertThat(ledgerRows(id)).isEqualTo(2);
        assertThat(paymentService.capture(id, key).httpStatus()).isEqualTo(200);
    }

    /** S3: timeout, and the client never comes back. The background resolver finishes it. */
    @Test
    void timeoutNoRetry_pendingResolverFinishesIt() {
        UUID id = authorize(6_000);
        failures.set(ProcessorName.OLD, FailureMode.TIMEOUT_AFTER_SUCCESS, 1.0);
        paymentService.capture(id, "cap-" + UUID.randomUUID());
        failures.reset();

        pendingResolver.resolveOlderThan(0);
        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(1);
        assertThat(ledgerRows(id)).isEqualTo(2);
    }

    /** The capture never reached the processor (we crashed between transaction 1 and the call). */
    @Test
    void neverReachedProcessor_resolverSendsExactlyOnce() {
        UUID id = authorize(4_000);
        Payment p = paymentService.get(id);
        paymentRepository.markCapturePending(p.id(), p.version());   // transaction 1 happened, the call didn't

        assertThat(captureResolver.resolve(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(1);
        assertThat(ledgerRows(id)).isEqualTo(2);
        captureResolver.resolve(id);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(1);
        assertThat(ledgerRows(id)).isEqualTo(2);
    }

    /** Our service crashed right after the processor captured. The client retries with the same key. */
    @Test
    void crashAfterSuccess_retryTakesOverOnceLeaseExpires() {
        UUID id = authorize(9_000);
        String key = "cap-" + UUID.randomUUID();
        failures.set(ProcessorName.OLD, FailureMode.CRASH_AFTER_SUCCESS, 1.0);
        assertThatThrownBy(() -> paymentService.capture(id, key)).isInstanceOf(ProcessorExceptions.SimulatedCrashException.class);
        failures.reset();

        assertThatThrownBy(() -> paymentService.capture(id, key))
                .isInstanceOf(ApiException.IdempotencyInProgressException.class);

        // Pretend 30 seconds passed: expire the lease.
        jdbc.update("UPDATE idempotency_keys SET locked_until = now() - interval '1 second' WHERE key = ?", key);

        StoredResponse retry = paymentService.capture(id, key);
        assertThat(retry.httpStatus()).isEqualTo(200);
        assertThat(retry.body().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(1);
        assertThat(ledgerRows(id)).isEqualTo(2);
    }

    private UUID authorize(long amount) {
        return paymentService.authorize("auth-" + UUID.randomUUID(),
                new AuthRequest("m-" + UUID.randomUUID(), amount, "EUR")).body().id();
    }

    private int ledgerRows(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId);
    }
}