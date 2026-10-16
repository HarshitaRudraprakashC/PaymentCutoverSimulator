package org.payment.simulation;

import lombok.RequiredArgsConstructor;
import org.payment.job.ReconciliationJob;
import org.payment.migration.MigrationTotals;
import org.payment.migration.MigrationTotals.Totals;
import org.payment.model.idempotency.StoredResponse;
import org.payment.model.processor.ProcessorName;
import org.payment.model.request.AuthRequest;
import org.payment.processor.FailureInjector;
import org.payment.processor.FailureMode;
import org.payment.repository.RolloutRepository;
import org.payment.service.PaymentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Safe vs naive: the same payments, the same timeouts, two ways of capturing.
 *
 *   safe  = PaymentService.capture, and on 202 the client retries with the SAME key
 *   naive = NaiveCapture: blind re-send on timeout, give up after 3
 *
 * Everything runs in-process (no HTTP) on the OLD processor, the one with no duplicate check.
 * All counts come from the database afterwards (MigrationTotals + reconciliation), not from
 * the code being measured.
 */
@Component
@RequiredArgsConstructor
public class Simulation {

    /** One mode's results. Latencies are per capture, including any retries, in milliseconds. */
    public record RunResult(String mode, int payments, int timeouts, int unknownResolved,
                            int duplicateCharges, int lostCharges, int problemsFound,
                            double p50Ms, double p95Ms) {}

    public record SimulationReport(RunResult safe, RunResult naive, List<String> table) {}

    /** What one capture did, in either mode. */
    private record CaptureResult(int timeouts, int unknownResolved) {}

    private final PaymentService paymentService;
    private final NaiveCapture naiveCapture;
    private final FailureInjector failures;
    private final RolloutRepository rollout;
    private final ReconciliationJob reconciliation;
    private final MigrationTotals totals;
    private final JdbcTemplate jdbc;

    @Value("${cutover.simulation.payments:10000}")
    private int paymentCount;

    @Value("${cutover.simulation.timeout-rate:0.05}")
    private double timeoutRate;


    public SimulationReport run() {
        int previousPercent = rollout.newPercent();
        rollout.setNewPercent(0);                 // everything on OLD: the processor that charges twice
        try {
            RunResult safe = runOnce("safe", this::safeCapture);
            RunResult naive = runOnce("naive", this::naiveCapture);
            List<String> table = table(safe, naive);
            table.forEach(System.out::println);
            return new SimulationReport(safe, naive, table);
        } finally {
            failures.reset();
            rollout.setNewPercent(previousPercent);
        }
    }

    private RunResult runOnce(String mode, Function<UUID, CaptureResult> capture) {
        String prefix = "sim-" + mode + "-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        long[] latencyNanos = new long[paymentCount];
        int timeouts = 0;
        int unknownResolved = 0;

        failures.set(ProcessorName.OLD, FailureMode.TIMEOUT_AFTER_SUCCESS, timeoutRate);
        for (int i = 0; i < paymentCount; i++) {
            UUID id = paymentService.authorize("auth-" + UUID.randomUUID(),
                    new AuthRequest(prefix + (i % 100), 1_000 + (i * 37L) % 10_000, "EUR")).body().id();

            long start = System.nanoTime();
            CaptureResult r = capture.apply(id);
            latencyNanos[i] = System.nanoTime() - start;

            timeouts += r.timeouts();
            unknownResolved += r.unknownResolved();
        }
        failures.reset();

        reconciliation.run();
        Totals t = totals.compute(prefix);
        Arrays.sort(latencyNanos);
        return new RunResult(mode, t.payments(), timeouts, unknownResolved, t.duplicateCharges(), t.lostCharges(),
                problemsFound(prefix), percentileMs(latencyNanos, 50), percentileMs(latencyNanos, 95));
    }

    /** What a well-behaved client does with our API: on 202, retry with the SAME key. */
    private CaptureResult safeCapture(UUID paymentId) {
        String key = "cap-" + UUID.randomUUID();
        StoredResponse answer = paymentService.capture(paymentId, key);
        boolean unknown = answer.httpStatus() == 202;
        for (int retry = 0; retry < 3 && answer.httpStatus() == 202; retry++) {
            answer = paymentService.capture(paymentId, key);
        }
        return new CaptureResult(unknown ? 1 : 0, unknown && answer.httpStatus() == 200 ? 1 : 0);
    }

    private CaptureResult naiveCapture(UUID paymentId) {
        NaiveCapture.Outcome outcome = naiveCapture.capture(paymentId);
        return new CaptureResult(outcome.timeouts(), 0);   // naive never "resolves": it guesses
    }

    /** Problems reconciliation recorded for this run's payments. */
    private int problemsFound(String prefix) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM recon_mismatches m
                  JOIN payments p ON p.id = m.payment_id
                 WHERE p.merchant_id LIKE ?
                """, Integer.class, prefix + "%");
    }

    private static double percentileMs(long[] sortedNanos, int percentile) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int index = Math.min(sortedNanos.length - 1, (int) Math.ceil(percentile / 100.0 * sortedNanos.length) - 1);
        return Math.round(sortedNanos[Math.max(index, 0)] / 100_000.0) / 10.0;   // ms, one decimal
    }

    /** Printed as a Markdown table, ready to paste into the README. */
    private List<String> table(RunResult safe, RunResult naive) {
        List<String> rows = new ArrayList<>();
        rows.add("");
        rows.add("Safe vs naive: " + safe.payments() + " payments each on the OLD processor, "
                + Math.round(timeoutRate * 100) + "% timeouts after capture");
        rows.add("");
        rows.add("| Metric | Safe | Naive |");
        rows.add("|---|---:|---:|");
        rows.add("| Timeouts (unknown outcomes) | " + safe.timeouts() + " | " + naive.timeouts() + " |");
        rows.add("| Unknown outcomes resolved | " + safe.unknownResolved() + " | - |");
        rows.add("| Duplicate charges | " + safe.duplicateCharges() + " | " + naive.duplicateCharges() + " |");
        rows.add("| Lost charges | " + safe.lostCharges() + " | " + naive.lostCharges() + " |");
        rows.add("| Problems found by reconciliation | " + safe.problemsFound() + " | " + naive.problemsFound() + " |");
        rows.add("| Capture latency p50 / p95 (ms) | " + safe.p50Ms() + " / " + safe.p95Ms()
                + " | " + naive.p50Ms() + " / " + naive.p95Ms() + " |");
        return rows;
    }
}