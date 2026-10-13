package org.payment.processor;

import org.payment.model.processor.ProcessorName;
import org.payment.model.processor.ProcessorStatus;

public interface Processor {

    ProcessorName name();

    /**
     * Capture money. Returning normally means "captured".
     * @throws "ProcessorExceptions.ProcessorFailedException"  nothing was captured
     * @throws "ProcessorExceptions.ProcessorTimeoutException" outcome unknown
     */
    void capture(String reference, long amountMinor);

    /** "What do you have for this reference?" Used by the resolver in step 5. */
    ProcessorStatus getStatus(String reference);
}
