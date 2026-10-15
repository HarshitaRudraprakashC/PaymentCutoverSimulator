package org.payment.migration;

import lombok.RequiredArgsConstructor;
import org.payment.migration.MigrationApiClient.ApiMismatch;
import org.payment.migration.MigrationApiClient.ApiPayment;
import org.payment.migration.MigrationApiClient.CaptureAnswer;
import org.payment.migration.MigrationApiClient.GateAnswer;
import org.payment.migration.MigrationTotals.Totals;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Drives a full 0% -> 100% cutover through the real API and prints what happened.
 *
 *   1  0 -> 5%     gate passes, send a batch
 *   2  5 -> 25%    gate passes, send a batch
 *   3  25%         bug switched on: NEW captures 1 cent less on some payments
 *   4  25 -> 50%   gate BLOCKS: reconciliation found the bug
 *   5  25%         bug fixed, an operator resolves the problems
 *   6  25 -> 50%   gate passes, send a batch
 *   7  50 -> 0%    rollback (always allowed) with authorized payments in flight on NEW
 *   8  0%          capture those: they all finish on NEW, none stranded
 *   9  0 -> 100%   step back up, one batch per step
 *
 * Timeouts are switched on for both processors the whole time, so "unknown outcome"
 * captures happen throughout and must be resolved without double charging.
 */
@Component
@RequiredArgsConstructor
public class MigrationRunner {

    private final RestClient.Builder http;
    private final MigrationTotals totals;

    @Value("${cutover.migration.batch-size:1000}")
    private int batchSize;

    @Value("${cutover.migration.merchants:200}")
    private int merchantCount;

    @Value("${cutover.migration.timeout-rate:0.02}")
    private double timeoutRate;

    @Value("${cutover.migration.bug-rate:0.05}")
    private double bugRate;

    @Value("${cutover.migration.drift-wait-seconds:30}")
    private int driftWaitSeconds;


    /** What one batch did. */
    private record Batch(int payments, int onNew, int timeoutsResolved) {}

    public MigrationReport run(String baseUrl) {
        MigrationApiClient api = new MigrationApiClient(http.baseUrl(baseUrl).build());
        String prefix = "mig-" + UUID.randomUUID().toString().substring(0, 6) + "-";
        List<String> merchants = IntStream.range(0, merchantCount).mapToObj(i -> prefix + i).toList();
        List<String> log = new ArrayList<>();
        int[] unknownResolved = {0};

        // ---- setup: start at 0%, timeouts on for both processors, clear leftovers from earlier runs
        api.setRollout(0);
        api.setFailure("OLD", "TIMEOUT_AFTER_SUCCESS", timeoutRate);
        api.setFailure("NEW", "TIMEOUT_AFTER_SUCCESS", timeoutRate);
        api.runRecon();                                           // record anything already wrong, then clear it
        int leftovers = resolveOpenOnNew(api, "migration-runner", "Leftover from an earlier run, cleared before starting");

        line(log, "Migration run " + prefix + "  (batch " + batchSize + ", " + merchantCount + " merchants, "
                + pct(timeoutRate) + " timeouts" + (leftovers > 0 ? ", " + leftovers + " leftover problems cleared" : "") + ")");
        line(log, "");
        line(log, "step  rollout     gate        what happened");

        // ---- 1 and 2: the first two steps up
        for (int[] move : new int[][]{{1, 0, 5}, {2, 5, 25}}) {
            GateAnswer gate = moveUp(api, move[2]);
            if (!gate.allowed()) {
                return abort(log, move[0], move[1], move[2], gate, unknownResolved[0]);
            }
            Batch batch = sendBatch(api, merchants, unknownResolved);
            row(log, move[0], move[1] + " -> " + move[2] + "%", "PASS", describe(batch));
        }

        // ---- 3: the planted bug
        api.setFailure("NEW", "UNDER_CAPTURE", bugRate);
        Batch buggy = sendBatch(api, merchants, unknownResolved);
        List<ApiMismatch> found = api.runRecon().mismatches().stream()
                .filter(m -> m.processor().equals("NEW") && m.type().equals("AMOUNT_MISMATCH") && !m.resolved())
                .toList();
        row(log, 3, "25%", "-", "bug ON: NEW captures 1 cent less on " + pct(bugRate) + " of payments; "
                + describe(buggy));

        // ---- 4: the gate must refuse to go further
        GateAnswer blocked = moveUp(api, 50);
        boolean blockedByGate = !blocked.allowed();
        if (blockedByGate) {
            row(log, 4, "25 -> 50%", "BLOCKED", "reconciliation found " + found.size() + " amount mismatch(es) on NEW");
        } else {
            row(log, 4, "25 -> 50%", "PASS (!)", "NOT blocked: the bug didn't fire (raise cutover.migration.bug-rate)");
            api.setRollout(25);                                   // going down is always allowed
        }

        // ---- 5: fix the bug, an operator handles each problem
        api.setFailure("NEW", "TIMEOUT_AFTER_SUCCESS", timeoutRate);
        int resolved = resolveOpenOnNew(api, "operator", "NEW under-captured by 1 cent; bug fixed, difference refunded");
        row(log, 5, "25%", "-", "bug fixed, " + resolved + " problem(s) resolved by an operator");

        // ---- 6: now the gate lets us through
        GateAnswer six = moveUp(api, 50);
        if (!six.allowed()) {
            return abort(log, 6, 25, 50, six, unknownResolved[0]);
        }
        row(log, 6, "25 -> 50%", "PASS", describe(sendBatch(api, merchants, unknownResolved)));

        // ---- 7: authorize a batch, then roll back to 0% before capturing it
        List<ApiPayment> inFlight = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) {
            inFlight.add(api.authorize(merchants.get(i % merchants.size()), amountFor(i)));
        }
        int inFlightOnNew = (int) inFlight.stream().filter(p -> p.processor().equals("NEW")).count();
        api.setRollout(0);
        row(log, 7, "50 -> 0%", "(always ok)", "ROLLBACK with " + inFlightOnNew + " authorized payments in flight on NEW");

        // ---- 8: capture them at 0%: each one finishes on the processor it was authorized on
        int stranded = 0;
        for (ApiPayment p : inFlight) {
            String status = captureUntilKnown(api, p.id(), unknownResolved);
            if (!status.equals("CAPTURED")) {
                stranded++;
            }
        }
        row(log, 8, "0%", "-", "all " + inFlightOnNew + " in-flight payments on NEW captured, " + stranded + " stranded");

        // ---- 9: back up to 100%, one step and one batch at a time
        int from = 0;
        for (int to : new int[]{5, 25, 50, 100}) {
            GateAnswer gate = moveUp(api, to);
            if (!gate.allowed()) {
                return abort(log, 9, from, to, gate, unknownResolved[0]);
            }
            row(log, 9, from + " -> " + to + "%", "PASS", describe(sendBatch(api, merchants, unknownResolved)));
            from = to;
        }

        // ---- finish: settle, let Kafka catch up, then check everything from the database
        api.setFailure("OLD", "NONE", 0);
        api.setFailure("NEW", "NONE", 0);
        api.runSettlement();
        Totals t = waitForKafka(api, prefix);
        api.runRecon();

        line(log, "");
        line(log, "Totals: " + t.payments() + " payments"
                + " | unknown outcomes resolved: " + unknownResolved[0]
                + " | duplicate charges: " + t.duplicateCharges()
                + " | lost charges: " + t.lostCharges()
                + " | ledger balanced: " + (t.unbalancedJournals() == 0 ? "yes" : "NO (" + t.unbalancedJournals() + ")")
                + " | Kafka drift: " + t.kafkaDrift());

        MigrationReport report = new MigrationReport(log, t.payments(), unknownResolved[0], found.size(), blockedByGate,
                inFlightOnNew, stranded, t.duplicateCharges(), t.lostCharges(), t.unbalancedJournals(),
                t.kafkaDrift(), 100);
        line(log, report.passed() ? "RESULT: PASS" : "RESULT: FAIL");
        return report;
    }

    // ---------------------------------------------------------------- the pieces

    /** Going up: run reconciliation first (the gate wants a recent run), then ask. */
    private GateAnswer moveUp(MigrationApiClient api, int to) {
        api.runRecon();
        return api.setRollout(to);
    }

    /** Authorize and capture batchSize payments, spread over the merchants. */
    private Batch sendBatch(MigrationApiClient api, List<String> merchants, int[] unknownResolved) {
        int before = unknownResolved[0];
        int onNew = 0;
        for (int i = 0; i < batchSize; i++) {
            ApiPayment p = api.authorize(merchants.get(i % merchants.size()), amountFor(i));
            if (p.processor().equals("NEW")) {
                onNew++;
            }
            captureUntilKnown(api, p.id(), unknownResolved);
        }
        return new Batch(batchSize, onNew, unknownResolved[0] - before);
    }

    /**
     * What a well-behaved client does: capture, and on 202 ("we don't know yet") retry with
     * the SAME idempotency key. The server resolves the unknown outcome before answering.
     */
    private String captureUntilKnown(MigrationApiClient api, UUID paymentId, int[] unknownResolved) {
        String key = "cap-" + UUID.randomUUID();
        CaptureAnswer answer = api.capture(paymentId, key);
        boolean wasUnknown = answer.httpStatus() == 202;
        for (int retry = 0; retry < 3 && answer.httpStatus() == 202; retry++) {
            answer = api.capture(paymentId, key);
        }
        if (wasUnknown && answer.httpStatus() == 200) {
            unknownResolved[0]++;
        }
        return answer.payment().status();
    }

    private int resolveOpenOnNew(MigrationApiClient api, String by, String note) {
        int count = 0;
        for (ApiMismatch m : api.openMismatches()) {
            if (m.processor().equals("NEW")) {
                api.resolve(m.id(), by, note);
                count++;
            }
        }
        return count;
    }

    /** Events reach the consumer a moment after the change. Send them all, then wait until the view agrees. */
    private Totals waitForKafka(MigrationApiClient api, String prefix) {
        long deadline = System.currentTimeMillis() + driftWaitSeconds * 1000L;
        while (true) {
            api.publishAllEvents();
            Totals t = totals.compute(prefix);
            if (t.kafkaDrift() == 0 || System.currentTimeMillis() > deadline) {
                return t;
            }
            sleep(500);
        }
    }

    private MigrationReport abort(List<String> log, int step, int from, int to, GateAnswer gate, int unknownResolved) {
        row(log, step, from + " -> " + to + "%", "BLOCKED", "unexpected, stopping: " + gate.message());
        line(log, "RESULT: FAIL");
        return new MigrationReport(log, 0, unknownResolved, 0, false, 0, 0, 0, 0, 0, 0, from);
    }

    /** Amounts between 10.00 and 109.99, so totals aren't all the same number. */
    private static long amountFor(int i) {
        return 1_000 + (i * 37L) % 10_000;
    }

    private static String describe(Batch b) {
        return b.payments() + " payments (" + b.onNew() + " on NEW), " + b.timeoutsResolved() + " timeouts resolved";
    }

    private static String pct(double rate) {
        return Math.round(rate * 100) + "%";
    }

    private static void row(List<String> log, int step, String rollout, String gate, String what) {
        line(log, String.format(" %-4d %-11s %-11s %s", step, rollout, gate, what));
    }

    private static void line(List<String> log, String text) {
        System.out.println(text);
        log.add(text);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}