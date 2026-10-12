package org.payment;

import org.junit.jupiter.api.Test;
import org.payment.model.exception.ApiException;
import org.payment.model.ledger.Direction;
import org.payment.model.payment.Payment;
import org.payment.model.request.AuthRequest;
import org.payment.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.payment.model.ledger.Direction.CREDIT;
import static org.payment.model.ledger.Direction.DEBIT;
import static org.payment.model.payment.PaymentStatus.AUTHORIZED;
import static org.payment.model.payment.PaymentStatus.CAPTURED;

/**
 * S1: the happy path. Authorize, capture, and the books balance.
 */
class CaptureHappyPathTest extends IntegrationTestBase {

    @Autowired
    PaymentService paymentService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void authorizeThenCapture_endsCapturedWithBalancedLedger() {
        UUID id = paymentService.authorize("auth-1", new AuthRequest("m-1", 10_000, "EUR")).body().id();
        Payment authorized = paymentService.get(id);
        assertEquals(AUTHORIZED, authorized.status());

        paymentService.capture(id, "cap-1");
        Payment captured = paymentService.get(id);        assertEquals(CAPTURED, captured.status());
        assertEquals(1, captured.captureAttempts());
        assertNull(captured.pendingSince());

        assertEquals(10_000, sumOf(authorized.id(), DEBIT.name()));
        assertEquals(10_000, sumOf(authorized.id(), CREDIT.name()));
        assertEquals(2, ledgerRows(authorized.id()));

    }

    @Test
    void capturingTwice_isRejected_andLedgerStillHasTwoRows() {
        UUID id = paymentService.authorize("auth-2", new AuthRequest("m-1", 10_000, "EUR")).body().id();
        Payment authorized = paymentService.get(id);
        paymentService.capture(authorized.id(), "cap-2");                     // ← first capture: must be here

        assertThatThrownBy(() -> paymentService.capture(authorized.id(), "cap-2b"))
                .isInstanceOf(ApiException.InvalidStateException.class);
        assertEquals(2, ledgerRows(authorized.id()));

    }

    private long sumOf(UUID paymentId, String direction) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM ledger_entries WHERE payment_id = ? AND direction = ?",
                Long.class, paymentId, direction);
    }

    private int ledgerRows(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId);
    }
}
