package org.payment.controller;


import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.payment.job.ReconciliationJob;
import org.payment.job.SettlementJob;
import org.payment.model.admin.AdminDTOs;
import org.payment.model.admin.AdminDTOs.MismatchResponse;
import org.payment.model.admin.AdminDTOs.ReconRunResponse;
import org.payment.model.admin.AdminDTOs.ResolveMismatchRequest;
import org.payment.model.admin.AdminDTOs.RolloutResponse;
import org.payment.model.exception.ApiException.InvalidStateException;
import org.payment.model.processor.ProcessorName;
import org.payment.processor.FailureInjector;
import org.payment.reconciliation.ReconRecords;
import org.payment.reconciliation.ReconRecords.Mismatch;
import org.payment.repository.ReconRepository;
import org.payment.repository.RolloutRepository;
import org.payment.service.RolloutService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Operator endpoints. In a real system these would need authentication.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final RolloutRepository rolloutRepository;
    private final FailureInjector failures;
    private final RolloutService rolloutService;
    private final SettlementJob settlementJob;
    private final ReconciliationJob reconciliationJob;
    private final ReconRepository recon;

    /**
     * PUT /admin/rollout {"newPercent": 25}. No gates yet: those come in step 6.
     */
    @PutMapping("/rollout")
    public RolloutResponse setRollout(@Valid @RequestBody AdminDTOs.RolloutRequest request) {
        rolloutService.moveTo(request.newPercent());
        return new RolloutResponse(rolloutRepository.newPercent(), Instant.now());
    }

    /**
     * PUT /admin/failures/NEW {"mode": "TIMEOUT_AFTER_SUCCESS", "rate": 1.0}
     */
    @PutMapping("/failures/{processor}")
    public ResponseEntity<Void> setFailure(@PathVariable ProcessorName processor,
                                           @Valid @RequestBody AdminDTOs.FailureConfigRequest request) {
        failures.set(processor, request.mode(), request.rate());
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /admin/settlement/run -> {"settled": 12}
     */
    @PostMapping("/settlement/run")
    public Map<String, Integer> runSettlement() {
        return Map.of("settled", settlementJob.settleAll());
    }

    /**
     * POST /admin/recon/run -> the run, plus every problem still open.
     */
    @PostMapping("/recon/run")
    public ReconRunResponse runRecon() {
        long runId = reconciliationJob.run();
        return toResponse(recon.findRun(runId).orElseThrow());
    }

    /**
     * GET /admin/recon/latest -> the last finished run, or 404 if none yet.
     */
    @GetMapping("/recon/latest")
    public ResponseEntity<ReconRunResponse> latestRecon() {
        return recon.latestRun()
                .map(run -> ResponseEntity.ok(toResponse(run)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /admin/recon/mismatches -> every open problem.
     */
    @GetMapping("/recon/mismatches")
    public List<MismatchResponse> openMismatches() {
        return recon.findOpen().stream().map(AdminController::toResponse).toList();
    }

    /**
     * POST /admin/recon/mismatches/{id}/resolve {"resolvedBy": "harshita", "note": "refunded, ticket 123"}
     * Records who handled it and why. Never changes money.
     */
    @PostMapping("/recon/mismatches/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable long id, @Valid @RequestBody ResolveMismatchRequest request) {
        if (!recon.resolve(id, request.resolvedBy(), request.note())) {
            throw new InvalidStateException("Mismatch " + id + " doesn't exist or is already resolved");
        }
        return ResponseEntity.noContent().build();
    }

    private ReconRunResponse toResponse(ReconRecords.ReconRun run) {
        return new ReconRunResponse(run.id(), run.startedAt(), run.finishedAt(), run.paymentsChecked(),
                openMismatches());
    }

    private static MismatchResponse toResponse(Mismatch m) {
        return new MismatchResponse(m.id(), m.paymentId(), m.processor().name(), m.type().name(),
                m.expected(), m.actual(), m.resolved());
    }
}

