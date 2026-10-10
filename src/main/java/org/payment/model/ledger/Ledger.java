package org.payment.model.ledger;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

public record Ledger(BigInteger id,
                     UUID journalId,
                     UUID paymentId,
                     long amountMinor,
                     EntryType entryType,
                     String account,
                     Direction direction,
                     Instant createdAt
){}
