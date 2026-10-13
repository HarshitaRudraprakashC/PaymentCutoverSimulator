package org.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.payment.model.exception.ProcessorExceptions;
import org.payment.model.idempotency.StoredResponse;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;
import org.payment.model.request.AuthRequest;
import org.payment.processor.FailureInjector;
import org.payment.processor.FailureMode;
import org.payment.processor.NewProcessor;
import org.payment.processor.OldProcessor;
import org.payment.repository.RolloutRepository;
import org.payment.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessorsTest extends IntegrationTestBase {

    @Autowired
    PaymentService paymentService;
    @Autowired
    RolloutRepository rollout;
    @Autowired FailureInjector failures;
    @Autowired OldProcessor oldProcessor;
    @Autowired NewProcessor newProcessor;
    @Autowired JdbcTemplate jdbc;

    /** The injector and the rollout are shared by every test in the run. Always switch them back. */
    @AfterEach
    void resetSwitches() {
        failures.reset();
        rollout.setNewPercent(0);
    }

    @Test
    void oldProcessorChargesTwice_newProcessorDoesNot() {
        String ref = UUID.randomUUID() + ":capture";
        oldProcessor.capture(ref, 1_000);
        oldProcessor.capture(ref, 1_000);
        newProcessor.capture(ref, 1_000);
        newProcessor.capture(ref, 1_000);

        assertThat(oldProcessor.getStatus(ref).captures()).isEqualTo(2);   // the customer paid twice
        assertThat(newProcessor.getStatus(ref).captures()).isEqualTo(1);
    }

    @Test
    void routingFollowsRolloutPercent() {
        rollout.setNewPercent(0);
        assertThat(paymentService.get(authorize(1_000)).processor()).isEqualTo(ProcessorName.OLD);
        rollout.setNewPercent(100);
        assertThat(paymentService.get(authorize(1_000)).processor()).isEqualTo(ProcessorName.NEW);
    }

    @Test
    void paymentStaysOnItsProcessor_whenRolloutChanges() {
        rollout.setNewPercent(100);
        UUID id = authorize(2_000);
        rollout.setNewPercent(0);
        paymentService.capture(id, "cap-" + UUID.randomUUID());
        assertThat(newProcessor.getStatus(id + ":capture").captures()).isEqualTo(1);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(0);

    }

    /** S6: the processor fails before capturing. */
    @Test
    void failBefore_returnsToAuthorized_andRetryWithSameKeySucceeds() {
        UUID id = authorize(5_000);                 // rollout is 0 -> OLD
        String key = "cap-" + UUID.randomUUID();
        failures.set(ProcessorName.OLD, FailureMode.FAIL_BEFORE, 1.0);

        assertThatThrownBy(() -> paymentService.capture(id, key)).isInstanceOf(ProcessorExceptions.ProcessorFailedException.class);
        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(0);

        failures.reset();
        StoredResponse retry = paymentService.capture(id, key);
        assertThat(retry.httpStatus()).isEqualTo(200);
        assertThat(retry.body().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(1);
        assertThat(ledgerRows(id)).isEqualTo(2);
    }

    /** The gap that step 5 closes. */
    @Test
    void timeoutAfterSuccess_answers202_moneyMovedButNotInOurLedger() {
        UUID id = authorize(8_000);
        failures.set(ProcessorName.OLD, FailureMode.TIMEOUT_AFTER_SUCCESS, 1.0);

        StoredResponse r = paymentService.capture(id, "cap-" + UUID.randomUUID());
        assertThat(r.httpStatus()).isEqualTo(202);
        assertThat(r.body().status()).isEqualTo(PaymentStatus.CAPTURE_PENDING);
        assertThat(oldProcessor.getStatus(id + ":capture").captures()).isEqualTo(1);
        assertThat(ledgerRows(id)).isEqualTo(0);
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