package org.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.payment.events.DeadLetterLog;
import org.payment.events.MerchantBalanceConsumer;
import org.payment.events.OutboxRelay;
import org.payment.events.OutboxRelay.RelayCrashException;
import org.payment.job.ReconciliationJob;
import org.payment.job.SettlementJob;
import org.payment.model.events.PaymentEvent;
import org.payment.model.events.PaymentEventType;
import org.payment.model.events.RelayFault;
import org.payment.model.exception.ProcessorExceptions.ProcessorFailedException;
import org.payment.model.payment.MismatchType;
import org.payment.model.processor.ProcessorName;
import org.payment.model.request.AuthRequest;
import org.payment.processor.FailureInjector;
import org.payment.processor.FailureMode;
import org.payment.reconciliation.ReconRecords.Mismatch;
import org.payment.repository.OutboxRepository;
import org.payment.repository.OutboxRepository.OutboxRow;
import org.payment.repository.ProjectionRepository;
import org.payment.repository.ReconRepository;
import org.payment.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class EventsTest extends IntegrationTestBase {

    @Autowired
    PaymentService paymentService;
    @Autowired
    SettlementJob settlementJob;
    @Autowired
    OutboxRepository outbox;
    @Autowired OutboxRelay relay;
    @Autowired MerchantBalanceConsumer consumer;
    @Autowired
    ProjectionRepository projection;
    @Autowired DeadLetterLog deadLetters;
    @Autowired
    ReconciliationJob reconciliationJob;
    @Autowired
    ReconRepository reconRepository;
    @Autowired FailureInjector failures;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void resetSwitches() {
        relay.setFault(RelayFault.NONE);
        failures.reset();
    }

    /** The outbox row is written in the same transaction as the capture, or not at all. */
    @Test
    void capture_writesExactlyOneEvent_failedCaptureWritesNone() {
        UUID ok = authorizeAndCapture("m-" + UUID.randomUUID(), 10_000);

        List<OutboxRow> rows = outbox.findByPayment(ok);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).type()).isEqualTo(PaymentEventType.PAYMENT_CAPTURED);
        assertThat(rows.get(0).paymentVersion()).isEqualTo(paymentService.get(ok).version());
        assertThat(rows.get(0).published()).isFalse();             // the relay hasn't run yet

        UUID refused = authorize("m-" + UUID.randomUUID(), 5_000);
        failures.set(ProcessorName.OLD, FailureMode.FAIL_BEFORE, 1.0);
        assertThatThrownBy(() -> paymentService.capture(refused, "cap-" + UUID.randomUUID()))
                .isInstanceOf(ProcessorFailedException.class);
        assertThat(outbox.findByPayment(refused)).isEmpty();      // nothing happened, nothing announced
    }

    @Test
    void event_reachesTheConsumer_andBuildsTheMerchantBalance() {
        String merchant = "m-" + UUID.randomUUID();
        UUID id = authorizeAndCapture(merchant, 10_000);

        publishAll();

        waitUntil(() -> projection.statusOf(id).isPresent());
        assertThat(projection.statusOf(id)).contains("CAPTURED");
        assertThat(projection.balanceOf(merchant).capturedMinor()).isEqualTo(10_000);
        assertThat(outbox.findByPayment(id).get(0).published()).isTrue();
    }

    /** S13: every event is sent twice. It's applied once. */
    @Test
    void everyEventSentTwice_isAppliedOnce() {
        String merchant = "m-" + UUID.randomUUID();
        UUID id = authorizeAndCapture(merchant, 4_000);
        int duplicatesBefore = consumer.duplicateCount();

        relay.setFault(RelayFault.DUPLICATE_PUBLISH);
        publishAll();

        waitUntil(() -> projection.statusOf(id).isPresent() && consumer.duplicateCount() > duplicatesBefore);
        assertThat(projection.balanceOf(merchant).capturedMinor()).isEqualTo(4_000);   // not 8_000
    }

    /** The relay sends, then "crashes" before marking the row sent. The event goes out again: still applied once. */
    @Test
    void crashBeforeMark_sendsAgain_stillAppliedOnce() {
        String merchant = "m-" + UUID.randomUUID();
        UUID id = authorizeAndCapture(merchant, 6_000);

        relay.setFault(RelayFault.CRASH_BEFORE_MARK);
        assertThatThrownBy(() -> relay.publishPending()).isInstanceOf(RelayCrashException.class);
        relay.setFault(RelayFault.NONE);
        assertThat(outbox.findByPayment(id).get(0).published()).isFalse();   // the crash undid the mark

        publishAll();

        waitUntil(() -> projection.statusOf(id).isPresent());
        assertThat(outbox.findByPayment(id).get(0).published()).isTrue();
        assertThat(projection.balanceOf(merchant).capturedMinor()).isEqualTo(6_000);
    }

    /** S14: an old event arrives after a newer one. It's ignored. */
    @Test
    void oldEventAfterNewerOne_isIgnored() throws Exception {
        String merchant = "m-" + UUID.randomUUID();
        UUID id = authorizeAndCapture(merchant, 9_000);
        settlementJob.settle(ProcessorName.OLD);                  // CAPTURED (v2) then SETTLED (v3)
        publishAll();
        waitUntil(() -> projection.statusOf(id).filter("SETTLED"::equals).isPresent());

        // Send the old CAPTURED event again, as a new message (for example from a replay tool).
        OutboxRow capturedRow = outbox.findByPayment(id).get(0);
        PaymentEvent old = json.readValue(capturedRow.payload(), PaymentEvent.class);
        PaymentEvent replayed = new PaymentEvent(UUID.randomUUID(), old.type(), old.paymentId(), old.paymentVersion(),
                old.merchantId(), old.amountMinor(), old.currency(), old.occurredAt());
        int staleBefore = consumer.staleCount();
        kafka.send("payment-events", id.toString(), json.writeValueAsString(replayed)).get();

        waitUntil(() -> projection.wasProcessed(replayed.eventId()));
        assertThat(projection.statusOf(id)).contains("SETTLED");  // the newer state stays
        assertThat(consumer.staleCount()).isGreaterThan(staleBefore);
        assertThat(projection.balanceOf(merchant).settledMinor()).isEqualTo(9_000);
        assertThat(projection.balanceOf(merchant).capturedMinor()).isZero();
    }

    /** S15: a broken event goes to the dead-letter topic, and reconciliation flags the gap it leaves. */
    @Test
    void brokenEvent_goesToDeadLetters_andReconFlagsDrift() {
        publishAll();               // send everything already waiting first, so only OUR event is broken
        UUID id = authorizeAndCapture("m-" + UUID.randomUUID(), 3_000);
        int deadBefore = deadLetters.received().size();

        relay.setFault(RelayFault.POISON);
        publishAll();
        relay.setFault(RelayFault.NONE);

        waitUntil(() -> deadLetters.received().size() > deadBefore);
        assertThat(projection.statusOf(id)).isEmpty();            // the consumer never applied it

        // Pretend this happened 2 hours ago, past the drift check's grace period.
        jdbc.update("UPDATE payments SET updated_at = now() - interval '2 hours' WHERE id = ?", id);
        reconciliationJob.run();

        List<Mismatch> drift = reconRepository.findByPayment(id).stream()
                .filter(m -> m.type() == MismatchType.PROJECTION_DRIFT).toList();
        assertThat(drift).hasSize(1);
        assertThat(drift.get(0).expected()).isEqualTo("CAPTURED");
        assertThat(drift.get(0).actual()).isEqualTo("missing");
        assertThat(drift.get(0).resolved()).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    /** Other test classes leave unsent events too, so keep sending until the outbox is empty. */
    private void publishAll() {
        while (relay.publishPending() > 0) {
            // keep going
        }
    }

    /** The consumer runs on its own thread, so wait (up to 20 s) for it to catch up. */
    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Condition not met within 20 seconds");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted");
            }
        }
    }

    private UUID authorize(String merchant, long amount) {
        return paymentService.authorize("auth-" + UUID.randomUUID(),
                new AuthRequest(merchant, amount, "EUR")).body().id();
    }

    private UUID authorizeAndCapture(String merchant, long amount) {
        UUID id = authorize(merchant, amount);
        paymentService.capture(id, "cap-" + UUID.randomUUID());
        return id;
    }
}