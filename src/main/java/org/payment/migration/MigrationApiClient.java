package org.payment.migration;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Calls OUR OWN API over HTTP, exactly like a merchant or an operator would.
 * The migration run goes through the real controllers, idempotency keys and gate:
 * nothing is called directly from Java.
 */
@RequiredArgsConstructor
public class MigrationApiClient {

    /** The parts of a payment response the runner needs. Extra JSON fields are ignored. */
    public record ApiPayment(UUID id, String merchantId, String processor, String status) {}

    /** A capture answer: the HTTP status (200 or 202) and the payment. */
    public record CaptureAnswer(int httpStatus, ApiPayment payment) {}

    public record ApiMismatch(long id, UUID paymentId, String processor, String type, boolean resolved) {}

    public record ApiReconRun(long runId, int paymentsChecked, List<ApiMismatch> mismatches) {}

    /** The answer to a rollout change: allowed, or blocked with the gate's reasons. */
    public record GateAnswer(boolean allowed, String message) {}

    private final RestClient http;

    // ---------------------------------------------------------------- payments (as a merchant)

    public ApiPayment authorize(String merchantId, long amountMinor) {
        return http.post().uri("/payments")
                .header("Idempotency-Key", "auth-" + UUID.randomUUID())
                .body(Map.of("merchantId", merchantId, "amount", amountMinor, "currency", "EUR"))
                .retrieve()
                .body(ApiPayment.class);
    }

    public CaptureAnswer capture(UUID paymentId, String idempotencyKey) {
        ResponseEntity<ApiPayment> response = http.post().uri("/payments/{id}/capture", paymentId)
                .header("Idempotency-Key", idempotencyKey)
                .retrieve()
                .toEntity(ApiPayment.class);
        return new CaptureAnswer(response.getStatusCode().value(), response.getBody());
    }

    // ---------------------------------------------------------------- operator endpoints

    /** PUT /admin/rollout. A 409 is not an error here: it's the gate saying no. */
    public GateAnswer setRollout(int percent) {
        try {
            http.put().uri("/admin/rollout").body(Map.of("newPercent", percent)).retrieve().toBodilessEntity();
            return new GateAnswer(true, "ok");
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                return new GateAnswer(false, e.getResponseBodyAsString());
            }
            throw e;
        }
    }

    public void setFailure(String processor, String mode, double rate) {
        http.put().uri("/admin/failures/{p}", processor)
                .body(Map.of("mode", mode, "rate", rate))
                .retrieve().toBodilessEntity();
    }

    public ApiReconRun runRecon() {
        return http.post().uri("/admin/recon/run").retrieve().body(ApiReconRun.class);
    }

    public List<ApiMismatch> openMismatches() {
        ApiMismatch[] open = http.get().uri("/admin/recon/mismatches").retrieve().body(ApiMismatch[].class);
        return open == null ? List.of() : List.of(open);
    }

    public void resolve(long mismatchId, String by, String note) {
        http.post().uri("/admin/recon/mismatches/{id}/resolve", mismatchId)
                .body(Map.of("resolvedBy", by, "note", note))
                .retrieve().toBodilessEntity();
    }

    public void runSettlement() {
        http.post().uri("/admin/settlement/run").retrieve().toBodilessEntity();
    }

    /** Sends every waiting outbox event now, instead of waiting for the relay's schedule. */
    public void publishAllEvents() {
        while (true) {
            Map<?, ?> answer = http.post().uri("/admin/events/publish").retrieve().body(Map.class);
            Object published = answer == null ? 0 : answer.get("published");
            if (!(published instanceof Number n) || n.intValue() == 0) {
                return;
            }
        }
    }
}