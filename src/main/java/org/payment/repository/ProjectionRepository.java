package org.payment.repository;

import lombok.RequiredArgsConstructor;
import org.payment.model.events.PaymentEvent;
import org.payment.model.events.PaymentEventType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * The consumer's own tables: which events it has handled, and its view of each payment.
 * Think of these as belonging to another team's service. They only ever change through Kafka.
 */
@Repository
@RequiredArgsConstructor
public class ProjectionRepository {

    public record MerchantBalance(String merchantId, long capturedMinor, long settledMinor) {}

    private final JdbcTemplate jdbc;

    /** Returns false if this event id was handled before: a duplicate. */
    public boolean markProcessed(UUID eventId) {
        int rows = jdbc.update(
                "INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING", eventId);
        return rows == 1;
    }

    /**
     * Store the payment's latest state. The WHERE on the update means an OLDER version never
     * overwrites a newer one. Returns false if the event was older than what we already have.
     *
     * We store the state ("this payment is SETTLED"), not a change ("add 100 to settled").
     * That's what makes order not matter: the newest version wins, whatever order events arrive in.
     */
    public boolean apply(PaymentEvent paymentEvent) {
        String status = paymentEvent.type() == PaymentEventType.PAYMENT_SETTLED ? "SETTLED" : "CAPTURED";
        int rows = jdbc.update("""
                INSERT INTO payment_projection (payment_id, merchant_id, status, amount_minor, version)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (payment_id) DO UPDATE
                   SET status = EXCLUDED.status,
                       amount_minor = EXCLUDED.amount_minor,
                       version = EXCLUDED.version,
                       updated_at = now()
                 WHERE payment_projection.version < EXCLUDED.version
                """, paymentEvent.paymentId(), paymentEvent.merchantId(), status, paymentEvent.amountMinor(), paymentEvent.paymentVersion());
        return rows == 1;
    }

    public Optional<String> statusOf(UUID paymentId) {
        return jdbc.queryForList("SELECT status FROM payment_projection WHERE payment_id = ?", String.class, paymentId)
                .stream().findFirst();
    }

    public boolean wasProcessed(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM processed_events WHERE event_id = ?)", Boolean.class, eventId);
    }

    public MerchantBalance balanceOf(String merchantId) {
        return jdbc.query("SELECT * FROM merchant_balances WHERE merchant_id = ?",
                        (rs, n) -> new MerchantBalance(merchantId, rs.getLong("captured_minor"), rs.getLong("settled_minor")),
                        merchantId)
                .stream().findFirst()
                .orElse(new MerchantBalance(merchantId, 0, 0));
    }
}
