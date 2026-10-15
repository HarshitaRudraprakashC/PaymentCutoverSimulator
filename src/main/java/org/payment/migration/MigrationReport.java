package org.payment.migration;

import java.util.List;

/** Everything the migration run measured. passed() is the one-line verdict. */
public record MigrationReport(
        List<String> log,
        int payments,
        int unknownOutcomesResolved,     // captures that answered 202, then resolved on retry
        int amountMismatchesFound,       // the planted bug, as found by reconciliation
        boolean blockedByGate,           // did the gate stop 25% -> 50% while the bug was live?
        int inFlightOnNewAtRollback,     // authorized on NEW, not yet captured, when we rolled back
        int stranded,                    // of those, how many could NOT be captured afterwards
        int duplicateCharges,
        int lostCharges,
        int unbalancedJournals,
        int kafkaDrift,
        int finalPercent
) {
    public boolean passed() {
        return blockedByGate
                && amountMismatchesFound > 0
                && finalPercent == 100
                && stranded == 0
                && duplicateCharges == 0
                && lostCharges == 0
                && unbalancedJournals == 0
                && kafkaDrift == 0;
    }
}