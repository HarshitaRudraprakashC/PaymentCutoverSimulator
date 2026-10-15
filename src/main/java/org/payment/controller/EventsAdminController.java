package org.payment.controller;


import lombok.RequiredArgsConstructor;
import org.payment.events.DeadLetterLog;
import org.payment.events.MerchantBalanceConsumer;
import org.payment.events.OutboxRelay;
import org.payment.model.events.RelayFault;
import org.payment.repository.ProjectionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Operator endpoints for the Kafka side. In a real system these would need authentication. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class EventsAdminController {

    private final OutboxRelay relay;
    private final MerchantBalanceConsumer consumer;
    private final DeadLetterLog deadLetters;
    private final ProjectionRepository projectionRepository;


    @PostMapping("/events/publish")
    public Map<String, Integer> publishNow() {
        return Map.of("published", relay.publishPending());
    }

    @PutMapping("/events/fault/{fault}")
    public ResponseEntity<Void> setFault(@PathVariable RelayFault fault) {
        relay.setFault(fault);
        return ResponseEntity.noContent().build();
    }

    /** GET /admin/events/stats -> what the consumer applied, and what it safely skipped. */
    @GetMapping("/events/stats")
    public Map<String, Integer> stats() {
        return Map.of(
                "applied", consumer.appliedCount(),
                "duplicatesSkipped", consumer.duplicateCount(),
                "staleSkipped", consumer.staleCount(),
                "deadLetters", deadLetters.received().size());
    }

    /** GET /admin/events/dead-letters -> the broken messages, as received. */
    @GetMapping("/events/dead-letters")
    public List<String> deadLetters() {
        return deadLetters.received();
    }

    /** GET /admin/merchants/m-1/balance -> the merchant's balance, as built from Kafka. */
    @GetMapping("/merchants/{merchantId}/balance")
    public ProjectionRepository.MerchantBalance balance(@PathVariable String merchantId) {
        return projectionRepository.balanceOf(merchantId);
    }
}
