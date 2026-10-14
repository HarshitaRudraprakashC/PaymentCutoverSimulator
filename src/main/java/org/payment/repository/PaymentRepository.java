package org.payment.repository;


import lombok.RequiredArgsConstructor;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.processor.ProcessorName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PaymentRepository {

    private final JdbcTemplate jdbc;

    /** Turns one database row into a Payment record. */
    private static final RowMapper<Payment> ROW_MAPPER = (rs, rowNum) -> new Payment(
            rs.getObject("id", UUID.class),
            rs.getString("merchant_id"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            ProcessorName.valueOf(rs.getString("processor")),
            PaymentStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            rs.getInt("capture_attempts"),
            toInstant(rs.getTimestamp("pending_since")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    public void insert(Payment p) {
        jdbc.update("""
                INSERT INTO payments (id, merchant_id, amount_minor, currency, processor, status)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                p.id(), p.merchantId(), p.amountMinor(), p.currency(),
                p.processor().name(), p.status().name());
    }

    public Optional<Payment> findById(UUID id) {
        return jdbc.query("SELECT * FROM payments WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    /** AUTHORIZED -> CAPTURE_PENDING. Returns false if someone changed the payment first. */
    public boolean markCapturePending(UUID id, int expectedVersion){
        int rows = jdbc.update("""
            UPDATE payments
               SET status = 'CAPTURE_PENDING',
                   version = version + 1,
                   capture_attempts = capture_attempts + 1,
                   pending_since = now(),
                   updated_at = now()
             WHERE id = ? AND status = 'AUTHORIZED' AND version = ?
            """, id, expectedVersion);
        return rows == 1;
    }

    /** CAPTURE_PENDING -> CAPTURED. Same rule. */
    public boolean markCaptured(UUID id, int expectedVersion) {
        int rows = jdbc.update("""
            UPDATE payments
               SET status = 'CAPTURED',
                   version = version + 1,
                   pending_since = NULL,
                   updated_at = now()
             WHERE id = ? AND status = 'CAPTURE_PENDING' AND version = ?
            """, id, expectedVersion);
        return rows == 1;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * CAPTURE_PENDING -> AUTHORIZED: the processor told us for certain that nothing was captured,
     * so the payment can be captured again later.
     */
    public boolean markCaptureFailed(UUID id, int expectedVersion) {
        int rows = jdbc.update("""
            UPDATE payments
               SET status = 'AUTHORIZED',
                   version = version + 1,
                   pending_since = NULL,
                   updated_at = now()
             WHERE id = ? AND status = 'CAPTURE_PENDING' AND version = ?
            """, id, expectedVersion);
        return rows == 1;
    }

    /**
     * Claim the right to send a capture AGAIN for a payment stuck in CAPTURE_PENDING.
     * Only one caller can win (version check), so two resolvers can never both re-send.
     * Also restarts the pending clock, so the scheduled resolver waits before looking again.
     */
    public boolean markResend(UUID id, int expectedVersion) {
        int rows = jdbc.update("""
            UPDATE payments
               SET version = version + 1,
                   capture_attempts = capture_attempts + 1,
                   pending_since = now(),
                   updated_at = now()
             WHERE id = ? AND status = 'CAPTURE_PENDING' AND version = ?
            """, id, expectedVersion);
        return rows == 1;
    }

    /** Payments stuck in CAPTURE_PENDING for longer than minAgeSeconds, oldest first. */
    public List<UUID> findPendingOlderThan(long minAgeSeconds, int limit) {
        return jdbc.queryForList("""
            SELECT id FROM payments
             WHERE status = 'CAPTURE_PENDING'
               AND pending_since < now() - make_interval(secs => ?)
             ORDER BY pending_since
             LIMIT ?
            """, UUID.class, minAgeSeconds, limit);
    }
}