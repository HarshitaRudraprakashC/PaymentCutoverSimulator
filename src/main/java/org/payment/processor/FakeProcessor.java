package org.payment.processor;

import lombok.RequiredArgsConstructor;
import org.payment.model.processor.ProcessorStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared behaviour of both fake processors. Each keeps its own table: "the processor's system".
 *
 * Called with NO transaction open (see PaymentService), so every insert here commits at once,
 * like a real processor that has taken the money regardless of what we do next.
 */
@Component
@RequiredArgsConstructor
public abstract class FakeProcessor implements Processor {

    protected final JdbcTemplate jdbc;
    private final FailureInjector failures;

    /** The processor's own table: old_processor_txns or new_processor_txns. */
    protected abstract String table();

    /** Write the capture to this processor's table. This is where OLD and NEW differ. */
    protected abstract void recordCapture(String reference, long amountMinor);

    @Override
    public final void capture(String reference, long amountMinor) {
        failures.beforeCapture(name());
        recordCapture(reference, failures.amountToCapture(name(), amountMinor));
        failures.afterCapture(name());
    }

    @Override
    public ProcessorStatus getStatus(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) AS captures, COALESCE(SUM(amount_minor), 0) AS amount FROM " + table()
                        + " WHERE reference = ? AND type = 'CAPTURE'",
                (rs, n) -> new ProcessorStatus(rs.getInt("captures"), rs.getLong("amount")),
                reference);
    }
}