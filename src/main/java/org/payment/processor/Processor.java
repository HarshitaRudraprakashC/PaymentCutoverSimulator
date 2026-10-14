package org.payment.processor;

import org.payment.model.processor.ProcessorName;
import org.payment.model.processor.ProcessorStatus;

import java.util.List;

public interface Processor {

    ProcessorName name();

    /**
     * Capture money. Returning normally means "captured".
     * @throws "ProcessorExceptions.ProcessorFailedException"  nothing was captured
     * @throws "ProcessorExceptions.ProcessorTimeoutException" outcome unknown
     */
    void capture(String reference, long amountMinor);

    ProcessorStatus getStatus(String reference);

    /**
     * A real processor sends this as a daily settlement file. Settlement and reconciliation
     * compare it with our records.
     */
    List<ProcessorReportLine> report();
}
