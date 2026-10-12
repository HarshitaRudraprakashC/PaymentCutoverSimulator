package org.payment;

import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.Test;
import org.payment.model.exception.ApiException;
import org.payment.model.idempotency.StoredResponse;
import org.payment.model.payment.PaymentResponse;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.request.AuthRequest;
import org.payment.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IdempotencyTest extends IntegrationTestBase {

    @Autowired
    PaymentService paymentService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void authorizeTwiceWithSameKey_returnsSameAnswer_andCreatesOnePayment() {
        AuthRequest request = new AuthRequest("m-replay", 10_000, "EUR");
        StoredResponse first = paymentService.authorize("key-a", request);
        StoredResponse second = paymentService.authorize("key-a", request);

        assertEquals(201, first.httpStatus());
        assertEquals(201, second.httpStatus());
        assertThat(second.body().id()).isEqualTo(first.body().id());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payments WHERE merchant_id = 'm-replay'", Integer.class)).isEqualTo(1);
    }

    @Test
    void captureTwiceWithSameKey_returns200Both_andMoneyMovesOnce() {
        UUID id = paymentService.authorize("key-b", new AuthRequest("m-1", 5_000, "EUR")).body().id();
        StoredResponse first = paymentService.capture(id, "cap-b");
        StoredResponse second = paymentService.capture(id, "cap-b");

        assertEquals(200, first.httpStatus());
        assertEquals(200, second.httpStatus());
        assertThat(second.body().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertEquals(2, ledgerRows(id));
    }

    @Test
    void sameKeyDifferentAmount_isRejectedWith422() {
        paymentService.authorize("key-c", new AuthRequest("m-1", 1_000, "EUR"));
        AssertionsForClassTypes.assertThatThrownBy(() -> paymentService.authorize("key-c", new AuthRequest("m-1", 2_000, "EUR")))
        .isInstanceOf(ApiException.IdempotencyKeyReusedException.class);
    }

    @Test
    void sameKeyDifferentMerchant_isTwoSeparatePayments() {
        StoredResponse first = paymentService.authorize("key-d", new AuthRequest("m-x", 1_000, "EUR"));
        StoredResponse second = paymentService.authorize("key-d", new AuthRequest("m-y", 1_000, "EUR"));

        assertNotEquals(second.body().id(), first.body().id());
    }

    /** S4: two identical captures at the same moment. Only one may move money. */
    @Test
    void twoIdenticalCapturesAtOnce_onlyOneCaptureHappens() throws Exception {
        PaymentResponse auth = paymentService.authorize("key-race", new AuthRequest("m-1", 7_000, "EUR")).body();

        CountDownLatch startTogether = new CountDownLatch(1);
        Callable<String> attempt = () -> {
            startTogether.await();                      // both threads wait here...
            try {
                return paymentService.capture(auth.id(), "cap-race").body().status().name();
            } catch (ApiException.IdempotencyInProgressException e) {
                return "409";
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> a = pool.submit(attempt);
        Future<String> b = pool.submit(attempt);
        startTogether.countDown();                      // ...and are released at the same moment
        List<String> results = List.of(a.get(10, SECONDS), b.get(10, SECONDS));
        pool.shutdown();

        // Either both see CAPTURED (the second was a replay), or one gets 409 while the first is running.
        assertThat(results).contains("CAPTURED");
        assertThat(results).allMatch(r -> r.equals("CAPTURED") || r.equals("409"));
        // The part that matters: money moved exactly once.
        assertThat(ledgerRows(auth.id())).isEqualTo(2);
    }

    private int ledgerRows(UUID paymentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId);
    }
}