package org.payment.processor;

import org.payment.model.processor.ProcessorName;

public interface Processor {

    ProcessorName name();

    /**
     * Capture money. Returning normally means "captured".
     * From step 4, failures come back as exceptions (declined, timeout).
     *
     * @param reference fixed per payment, e.g. "<paymentId>:capture"
     */
    void capture(String reference, long amountMinor);
}
