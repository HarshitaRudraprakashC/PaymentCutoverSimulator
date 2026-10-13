package org.payment.model.processor;

/** What a processor says when asked "what do you have for this reference?". Used from step 5. */
public record ProcessorStatus(int captures, long capturedAmountMinor) {
    public boolean captured() {
        return captures > 0;
    }
}
