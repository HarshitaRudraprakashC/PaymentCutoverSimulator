package org.payment.repository;

import lombok.RequiredArgsConstructor;
import org.payment.model.ledger.Direction;
import org.payment.model.ledger.EntryType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LedgerRepository {

    private final JdbcTemplate jdbc;


    public void insertEntry(UUID journalId, UUID paymentId, String entryType,
                            String account, String direction, long amountMinor) {
        jdbc.update("""
                INSERT INTO ledger_entries (journal_id, payment_id, entry_type, account, direction, amount_minor)
                VALUES (?, ?, ?, ?, ?, ?)
                """, journalId, paymentId, entryType, account, direction, amountMinor);
    }
}
