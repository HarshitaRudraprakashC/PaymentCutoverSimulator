package org.payment.processor;

import org.payment.model.processor.ProcessorName;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NewProcessor extends FakeProcessor {

    public NewProcessor(JdbcTemplate jdbc, FailureInjector failures) {
        super(jdbc, failures);
    }

    @Override
    public ProcessorName name() {
        return ProcessorName.NEW;
    }

    @Override
    protected String table() {
        return "new_processor_txns";
    }

    @Override
    protected void recordCapture(String reference, long amountMinor) {
        jdbc.update("""
            INSERT INTO new_processor_txns (reference, type, amount_minor) VALUES (?, 'CAPTURE', ?)
            ON CONFLICT (reference, type) DO NOTHING
            """, reference, amountMinor);
    }
}
