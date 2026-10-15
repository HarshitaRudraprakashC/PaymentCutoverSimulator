package org.payment.repository;


import lombok.RequiredArgsConstructor;
import org.payment.model.events.PaymentEvent;
import org.payment.model.events.PaymentEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OutboxRepository {

    public record OutboxRow(long id, UUID eventId, UUID paymentId, int paymentVersion,
                            PaymentEventType type, String payload, boolean published) {}

    private final JdbcTemplate jdbc;

    /** Must be called inside the same transaction as the payment change it describes. */
    public void append(PaymentEvent event, String json) {
        jdbc.update("""
                INSERT INTO outbox_events (event_id, payment_id, payment_version, type, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, event.eventId(), event.paymentId(), event.paymentVersion(), event.type().name(), json);
    }

    /**
     * The oldest unsent events, locked for this transaction.
     * SKIP LOCKED: if two relays run at once, each takes different rows instead of waiting.
     */
    public List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.query("""
                SELECT id, event_id, payment_id, payment_version, type, payload::text AS payload, published_at
                  FROM outbox_events
                 WHERE published_at IS NULL
                 ORDER BY id
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED
                """, (rs, n) -> toRow(rs), limit);
    }

    public void markPublished(long id) {
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", id);
    }

    public List<OutboxRow> findByPayment(UUID paymentId) {
        return jdbc.query("""
                SELECT id, event_id, payment_id, payment_version, type, payload::text AS payload, published_at
                  FROM outbox_events
                 WHERE payment_id = ?
                 ORDER BY id
                """, (rs, n) -> toRow(rs), paymentId);
    }

    private static OutboxRow toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OutboxRow(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getInt("payment_version"),
                PaymentEventType.valueOf(rs.getString("type")),
                rs.getString("payload"),
                rs.getTimestamp("published_at") != null);
    }
}

