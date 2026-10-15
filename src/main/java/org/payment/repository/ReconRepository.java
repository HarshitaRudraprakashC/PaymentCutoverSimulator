package org.payment.repository;

import lombok.RequiredArgsConstructor;
import org.payment.model.payment.MismatchType;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;
import org.payment.reconciliation.ReconRecords;
import org.payment.reconciliation.ReconRecords.OurSide;
import org.payment.reconciliation.ReconRecords.ReconRun;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes recon_runs and recon_mismatches, and reads "our side" for a comparison.
 */
@Repository
@RequiredArgsConstructor
public class ReconRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<ReconRecords.Mismatch> MISMATCH = (rs, n) -> new ReconRecords.Mismatch(
            rs.getLong("id"),
            rs.getLong("run_id"),
            rs.getObject("payment_id", UUID.class),
            ProcessorName.valueOf(rs.getString("processor")),
            MismatchType.valueOf(rs.getString("type")),
            rs.getString("expected"),
            rs.getString("actual"),
            rs.getBoolean("resolved"),
            rs.getString("resolved_by"),
            rs.getString("resolution_note"));

    private static final RowMapper<ReconRun> RUN = (rs, n) -> new ReconRun(
            rs.getLong("id"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("finished_at")),
            rs.getInt("payments_checked"),
            rs.getInt("mismatches"));

    // ---------------------------------------------------------------- runs

    public long startRun() {
        return jdbc.queryForObject("INSERT INTO recon_runs DEFAULT VALUES RETURNING id", Long.class);
    }

    public void finishRun(long runId, int paymentsChecked, int mismatches) {
        jdbc.update("""
                UPDATE recon_runs
                   SET finished_at = now(), payments_checked = ?, mismatches = ?
                 WHERE id = ?
                """, paymentsChecked, mismatches, runId);
    }

    public Optional<ReconRun> findRun(long runId) {
        return jdbc.query("SELECT * FROM recon_runs WHERE id = ?", RUN, runId).stream().findFirst();
    }

    public Optional<ReconRun> latestRun() {
        return jdbc.query("SELECT * FROM recon_runs WHERE finished_at IS NOT NULL ORDER BY finished_at DESC LIMIT 1", RUN)
                .stream().findFirst();
    }

    /**
     * Used by the rollout gate: did a run FINISH in the last N minutes?
     */
    public boolean finishedWithinMinutes(long minutes) {
        return jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM recon_runs
                                WHERE finished_at > now() - make_interval(mins => ?))
                """, Boolean.class, (int) minutes);
    }

    // ---------------------------------------------------------------- our side

    /**
     * Every payment on this processor, with the capture amounts our ledger has for it.
     * In a real system this would be one day's payments, not all of them.
     */
    public List<OurSide> ourSide(ProcessorName processor) {
        return jdbc.query("""
                        SELECT p.id, p.status, p.amount_minor, p.pending_since,
                               COALESCE(l.debit, 0)  AS ledger_debit,
                               COALESCE(l.credit, 0) AS ledger_credit
                          FROM payments p
                          LEFT JOIN (SELECT payment_id,
                                            SUM(amount_minor) FILTER (WHERE direction = 'DEBIT')  AS debit,
                                            SUM(amount_minor) FILTER (WHERE direction = 'CREDIT') AS credit
                                       FROM ledger_entries
                                      WHERE entry_type = 'CAPTURE'
                                      GROUP BY payment_id) l ON l.payment_id = p.id
                         WHERE p.processor = ?
                        """,
                (rs, n) -> new OurSide(
                        rs.getObject("id", UUID.class),
                        PaymentStatus.valueOf(rs.getString("status")),
                        rs.getLong("amount_minor"),
                        toInstant(rs.getTimestamp("pending_since")),
                        rs.getLong("ledger_debit"),
                        rs.getLong("ledger_credit")),
                processor.name());
    }

    // ---------------------------------------------------------------- mismatches

    /**
     * Save an open problem. Returns false if this payment already has this kind of problem on record
     * (open or resolved): the unique index on (payment_id, type) makes the insert do nothing.
     */
    public boolean saveOpen(long runId, ReconRecords.Finding f) {
        int rows = jdbc.update("""
                INSERT INTO recon_mismatches (run_id, payment_id, processor, type, expected, actual)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (payment_id, type) DO NOTHING
                """, runId, f.paymentId(), f.processor().name(), f.type().name(), f.expected(), f.actual());
        return rows == 1;
    }

    /**
     * Save a problem that reconciliation already fixed, so there's still a record that it happened.
     */
    public void saveAutoResolved(long runId, ReconRecords.Finding f, String resolvedBy, String note) {
        jdbc.update("""
                        INSERT INTO recon_mismatches (run_id, payment_id, processor, type, expected, actual,
                                                      resolved, resolved_by, resolution_note, resolved_at)
                        VALUES (?, ?, ?, ?, ?, ?, true, ?, ?, now())
                        ON CONFLICT (payment_id, type) DO NOTHING
                        """, runId, f.paymentId(), f.processor().name(), f.type().name(), f.expected(), f.actual(),
                resolvedBy, note);
    }

    public List<ReconRecords.Mismatch> findOpen() {
        return jdbc.query("SELECT * FROM recon_mismatches WHERE NOT resolved ORDER BY id", MISMATCH);
    }

    public List<ReconRecords.Mismatch> findByPayment(UUID paymentId) {
        return jdbc.query("SELECT * FROM recon_mismatches WHERE payment_id = ? ORDER BY id", MISMATCH, paymentId);
    }

    public int countOpen(ProcessorName processor) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM recon_mismatches WHERE processor = ? AND NOT resolved",
                Integer.class, processor.name());
    }

    /**
     * Mark a problem as handled. Only records who and why: it never touches payments or the ledger.
     * Returns false if there's no such open problem (unknown id, or already resolved).
     */
    public boolean resolve(long id, String resolvedBy, String note) {
        int rows = jdbc.update("""
                UPDATE recon_mismatches
                   SET resolved = true, resolved_by = ?, resolution_note = ?, resolved_at = now()
                 WHERE id = ? AND NOT resolved
                """, resolvedBy, note, id);
        return rows == 1;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    public record DriftRow(UUID paymentId, ProcessorName processor, PaymentStatus status, String projectedStatus) {}

    /**
     * Only payments that last changed more than graceSeconds ago: newer ones may simply have
     * events still on their way.
     */
    public List<DriftRow> findProjectionDrift(long graceSeconds) {
        return jdbc.query("""
                SELECT p.id, p.processor, p.status, pp.status AS projected_status
                  FROM payments p
                  LEFT JOIN payment_projection pp ON pp.payment_id = p.id
                 WHERE p.status IN ('CAPTURED', 'SETTLED')
                   AND p.updated_at < now() - make_interval(secs => ?)
                   AND pp.status IS DISTINCT FROM p.status
                """,
                (rs, n) -> new DriftRow(
                        rs.getObject("id", UUID.class),
                        ProcessorName.valueOf(rs.getString("processor")),
                        PaymentStatus.valueOf(rs.getString("status")),
                        rs.getString("projected_status")),
                graceSeconds);
    }
}
