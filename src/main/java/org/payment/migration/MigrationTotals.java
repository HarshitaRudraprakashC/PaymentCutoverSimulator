package org.payment.migration;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The end-of-run checks, straight from the database. They compare our payments with BOTH
 * processors' own records, the ledger and the Kafka-built view: no trusting our own counters.
 *
 * Every query is limited to this run's payments, found by their merchant id prefix ("mig-ab12cd-").
 */
@Component
@RequiredArgsConstructor
public class MigrationTotals {

    public record Totals(int payments, int duplicateCharges, int lostCharges, int amountMismatches,
                         int notCaptured, int unbalancedJournals, int kafkaDrift) {}

    private final JdbcTemplate jdbc;

    public Totals compute(String merchantPrefix) {
        String like = merchantPrefix + "%";
        return jdbc.queryForObject("""
                WITH ours AS (
                    SELECT id, id::text || ':capture' AS ref, amount_minor, status
                      FROM payments
                     WHERE merchant_id LIKE ?
                ),
                theirs AS (
                    SELECT reference, count(*) AS n, SUM(amount_minor) AS amt FROM old_processor_txns GROUP BY reference
                    UNION ALL
                    SELECT reference, count(*) AS n, SUM(amount_minor) AS amt FROM new_processor_txns GROUP BY reference
                )
                SELECT count(*)                                                                  AS payments,
                       count(*) FILTER (WHERE t.n > 1)                                           AS duplicate_charges,
                       count(*) FILTER (WHERE (o.status IN ('CAPTURED', 'SETTLED')) <> (t.reference IS NOT NULL))
                                                                                                 AS lost_charges,
                       count(*) FILTER (WHERE t.n = 1 AND t.amt <> o.amount_minor)               AS amount_mismatches,
                       count(*) FILTER (WHERE o.status NOT IN ('CAPTURED', 'SETTLED'))           AS not_captured,
                       (SELECT count(*) FROM (SELECT journal_id FROM ledger_entries
                                               GROUP BY journal_id
                                              HAVING SUM(CASE direction WHEN 'DEBIT' THEN amount_minor
                                                                        ELSE -amount_minor END) <> 0) j)
                                                                                                 AS unbalanced_journals,
                       (SELECT count(*) FROM payments p
                          LEFT JOIN payment_projection pp ON pp.payment_id = p.id
                         WHERE p.merchant_id LIKE ?
                           AND p.status IN ('CAPTURED', 'SETTLED')
                           AND pp.status IS DISTINCT FROM p.status)                              AS kafka_drift
                  FROM ours o
                  LEFT JOIN theirs t ON t.reference = o.ref
                """,
                (rs, n) -> new Totals(
                        rs.getInt("payments"),
                        rs.getInt("duplicate_charges"),
                        rs.getInt("lost_charges"),
                        rs.getInt("amount_mismatches"),
                        rs.getInt("not_captured"),
                        rs.getInt("unbalanced_journals"),
                        rs.getInt("kafka_drift")),
                like, like);
    }
}