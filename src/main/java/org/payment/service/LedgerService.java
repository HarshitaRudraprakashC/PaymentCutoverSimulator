package org.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.ledger.Direction;
import org.payment.model.ledger.EntryType;
import org.payment.model.payment.Payment;
import org.payment.repository.LedgerRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static org.payment.model.ledger.Direction.CREDIT;
import static org.payment.model.ledger.Direction.DEBIT;
import static org.payment.model.ledger.EntryType.CAPTURE;

@Service
@Slf4j
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerRepository ledgerRepository;

    public void postCapture(Payment p) {
        log.info("Capturing the payment ledger");
        UUID id = UUID.randomUUID();

        String account = "PROCESSOR_RECEIVABLE:" + p.processor().name();
        ledgerRepository.insertEntry(id, p.id(), CAPTURE.name(), account, DEBIT.name(), p.amountMinor());

        ledgerRepository.insertEntry(id, p.id(), CAPTURE.name(), "MERCHANT_PAYABLE", CREDIT.name(), p.amountMinor());
    }

    /**
     * The processor paid out the €100 it owed us:
     *   DEBIT   CASH                       10000   (money arrived in our bank account)
     *   CREDIT  PROCESSOR_RECEIVABLE:OLD   10000   (the processor no longer owes us)
     * After capture + settlement, PROCESSOR_RECEIVABLE for this payment is back to 0.
     * Must be called inside the same transaction as the SETTLED status change.
     */
    public void postSettlement(Payment p) {
        UUID journalId = UUID.randomUUID();
        String receivable = "PROCESSOR_RECEIVABLE:" + p.processor().name();
        ledgerRepository.insertEntry(journalId, p.id(), "SETTLEMENT", "CASH", "DEBIT", p.amountMinor());
        ledgerRepository.insertEntry(journalId, p.id(), "SETTLEMENT", receivable, "CREDIT", p.amountMinor());
    }

}
