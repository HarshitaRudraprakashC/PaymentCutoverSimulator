package org.payment.processor;

import org.payment.model.processor.ProcessorName;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
    public class AlwaysSucceedsProcessor implements Processor {

        private final Map<String, Long> captured = new ConcurrentHashMap<>();

        @Override
        public ProcessorName name() {
            return ProcessorName.OLD;
        }

        @Override
        public void capture(String reference, long amountMinor) {
            captured.put(reference, amountMinor);
        }


        public Long capturedAmount(String reference) {
            return captured.get(reference);
        }
}
