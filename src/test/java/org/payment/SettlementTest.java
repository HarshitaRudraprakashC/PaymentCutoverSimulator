package org.payment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;
import org.payment.model.request.AuthRequest;
import org.payment.processor.FailureInjector;
import org.payment.processor.FailureMode;
import org.payment.service.PaymentService;
import org.payment.job.SettlementJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementTest extends IntegrationTestBase {

    @Autowired
    PaymentService paymentService;
    @Autowired SettlementJob settlementJob;
    @Autowired FailureInjector failures;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void resetSwitches() {
        failures.reset();
    }

    @Test
    void capturedPayment_settles_andReceivableGoesBackToZero() {
        UUID id = authorizeAndCapture(10_000);                 // rollout 0 -> OLD

        settlementJob.settle(ProcessorName.OLD);

        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.SETTLED);
        assertThat(ledgerRows(id)).isEqualTo(4);               // 2 for capture + 2 for settlement
        assertThat(balance(id, "CASH")).isEqualTo(10_000);     // the money arrived
        assertThat(balance(id, "PROCESSOR_RECEIVABLE:OLD")).isZero();   // OLD owes us nothing now
        assertThat(balance(id, "MERCHANT_PAYABLE")).isEqualTo(-10_000); // we still owe the merchant
    }

    @Test
    void settlingTwice_settlesOnce() {
        UUID id = authorizeAndCapture(3_000);

        settlementJob.settle(ProcessorName.OLD);
        settlementJob.settle(ProcessorName.OLD);

        assertThat(ledgerRows(id)).isEqualTo(4);
    }

    /** S10, part 1: a capture that doesn't match is never settled. */
    @Test
    void underCapturedPayment_isNotSettled() {
        failures.set(ProcessorName.OLD, FailureMode.UNDER_CAPTURE, 1.0);
        UUID id = authorizeAndCapture(10_000);                 // the processor captures 9_999
        failures.reset();

        settlementJob.settle(ProcessorName.OLD);

        assertThat(paymentService.get(id).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(ledgerRows(id)).isEqualTo(2);               // no settlement journal
    }

    private UUID authorizeAndCapture(long amount) {
        UUID id = paymentService.authorize("auth-" + UUID.randomUUID(),
                new AuthRequest("m-" + UUID.randomUUID(), amount, "EUR")).body().id();
        paymentService.capture(id, "cap-" + UUID.randomUUID());
        return id;
    }

    private int ledgerRows(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId);
    }

    /** Debits minus credits on one account, for one payment. */
    private long balance(UUID paymentId, String account) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE direction WHEN 'DEBIT' THEN amount_minor ELSE -amount_minor END), 0)
                  FROM ledger_entries
                 WHERE payment_id = ? AND account = ?
                """, Long.class, paymentId, account);
    }
}