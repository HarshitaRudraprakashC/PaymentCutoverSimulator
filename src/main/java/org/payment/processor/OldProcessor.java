package org.payment.processor;

import org.payment.model.processor.ProcessorName;
import org.payment.model.processor.ProcessorStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The processor we're migrating away from. It does NOT detect duplicates. */
@Component
public class OldProcessor extends FakeProcessor {

    public OldProcessor(JdbcTemplate jdbc, FailureInjector failures) {
        super(jdbc, failures);
    }

    @Override
    public ProcessorName name() {
        return ProcessorName.OLD;
    }

    @Override
    protected String table() {
        return "old_processor_txns";
    }

    @Override
    protected void recordCapture(String reference, long amountMinor) {
        jdbc.update("""
            INSERT INTO old_processor_txns (reference, type, amount_minor) VALUES (?, 'CAPTURE', ?)
            """, reference, amountMinor);
    }
}
