package org.payment.repository;


import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {

    /** One row of idempotency_keys. responseBody is raw JSON text (null until COMPLETED). */
    public record KeyRecord(String requestHash, String state, boolean leaseExpired,
                            Integer responseCode, String responseBody) {}

    private static final RowMapper<KeyRecord> ROW_MAPPER = (rs, n) -> new KeyRecord(
            rs.getString("request_hash"),
            rs.getString("state"),
            rs.getBoolean("lease_expired"),
            (Integer) rs.getObject("response_code"),
            rs.getString("response_body"));

    private final JdbcTemplate jdbc;

    /**
     * Try to claim the key. Returns true if WE inserted it (first time this key is seen).
     * If another request inserted the same key and hasn't committed yet, Postgres makes this
     * statement WAIT for it, then returns false. That's what makes two identical requests safe.
     */
    public boolean tryClaim(String merchantId, String key, String requestHash) {
        int rows = jdbc.update("""
                INSERT INTO idempotency_keys (merchant_id, key, request_hash, state, locked_until)
                VALUES (?, ?, ?, 'IN_PROGRESS', now() + interval '30 seconds')
                ON CONFLICT (merchant_id, key) DO NOTHING
                """, merchantId, key, requestHash);
        return rows == 1;
    }

    public Optional<KeyRecord> find(String merchantId, String key) {
        return jdbc.query("""
                SELECT request_hash, state, locked_until < now() AS lease_expired,
                       response_code, response_body::text AS response_body
                  FROM idempotency_keys
                 WHERE merchant_id = ? AND key = ?
                """, ROW_MAPPER, merchantId, key).stream().findFirst();
    }

    /** Save the answer and mark the key COMPLETED. */
    public void complete(String merchantId, String key, UUID paymentId, int responseCode, String responseBodyJson) {
        jdbc.update("""
                UPDATE idempotency_keys
                   SET state = 'COMPLETED', payment_id = ?, response_code = ?, response_body = CAST(? AS jsonb)
                 WHERE merchant_id = ? AND key = ?
                """, paymentId, responseCode, responseBodyJson, merchantId, key);
    }


    /** Forget the key: the request had no effect, so a retry with the same key should run again. */
    public void release(String merchantId, String key) {
        jdbc.update("DELETE FROM idempotency_keys WHERE merchant_id = ? AND key = ?", merchantId, key);
    }

    /**
     * The request holding this key crashed (its lease ran out). Take the key over by extending
     * the lease. Only one retry can win, because the WHERE still requires an expired lease.
     */
    public boolean takeOver(String merchantId, String key) {
        int rows = jdbc.update("""
            UPDATE idempotency_keys
               SET locked_until = now() + interval '30 seconds'
             WHERE merchant_id = ? AND key = ?
               AND state = 'IN_PROGRESS' AND locked_until < now()
            """, merchantId, key);
        return rows == 1;
    }

}
