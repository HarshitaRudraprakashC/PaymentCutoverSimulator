package org.payment.model.admin;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class AdminDTOs {

    private AdminDTOs(){}

    public record RolloutRequest(@Min(0) @Max(100) int newPercent) {}

    public record RolloutResponse(int newPercent, Instant updatedAt) {}

    public record FailureConfigRequest(
            @NotNull FailureMode mode,
            @DecimalMin("0.0") @DecimalMax("1.0") double rate
    ) {}

    public record MismatchResponse(
            long id,
            UUID paymentId,
            String processor,
            String type,
            String expected,
            String actual,
            boolean resolved
    ) {}


    public record ReconRunResponse(
            long runId,
            Instant startedAt,
            Instant finishedAt,
            int paymentsChecked,
            List<MismatchResponse> mismatches
    ) {}

    public record ResolveMismatchRequest(@NotBlank String resolvedBy, @NotBlank String note) {}
}
