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

}
