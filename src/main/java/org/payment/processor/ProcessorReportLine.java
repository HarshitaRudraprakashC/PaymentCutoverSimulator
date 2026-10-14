package org.payment.processor;

/**
 * One line of a processor's report: everything it captured for one reference.
 * captures > 1 means the processor charged more than once for the same payment.
 */
public record ProcessorReportLine(String reference, int captures, long amountMinor) {}