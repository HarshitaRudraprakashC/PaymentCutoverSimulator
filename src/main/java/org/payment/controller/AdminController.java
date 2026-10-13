package org.payment.controller;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.payment.model.admin.AdminDTOs;
import org.payment.model.processor.ProcessorName;
import org.payment.processor.FailureInjector;
import org.payment.repository.RolloutRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Operator endpoints. In a real system these would need authentication. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RolloutRepository rollout;
    private final FailureInjector failures;

    /** PUT /admin/rollout {"newPercent": 25}. No gates yet: those come in step 6. */
    @PutMapping("/rollout")
    public AdminDTOs.RolloutResponse setRollout(@Valid @RequestBody AdminDTOs.RolloutRequest request) {
        rollout.setNewPercent(request.newPercent());
        return new AdminDTOs.RolloutResponse(rollout.newPercent(), Instant.now());
    }

    /** PUT /admin/failures/NEW {"mode": "TIMEOUT_AFTER_SUCCESS", "rate": 1.0} */
    @PutMapping("/failures/{processor}")
    public ResponseEntity<Void> setFailure(@PathVariable ProcessorName processor,
                                           @Valid @RequestBody AdminDTOs.FailureConfigRequest request) {
        failures.set(processor, request.mode(), request.rate());
        return ResponseEntity.noContent().build();
    }
}
