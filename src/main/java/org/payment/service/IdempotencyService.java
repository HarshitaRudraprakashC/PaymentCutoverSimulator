package org.payment.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.exception.ApiException;
import org.payment.model.idempotency.IdempotentState;
import org.payment.model.idempotency.StoredResponse;
import org.payment.model.payment.PaymentResponse;
import org.payment.repository.IdempotencyRepository;
import org.payment.repository.IdempotencyRepository.KeyRecord;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

import static org.payment.model.idempotency.IdempotentState.COMPLETED;
import static org.payment.model.idempotency.IdempotentState.IN_PROGRESS;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final IdempotencyRepository idempRepo;
    private final ObjectMapper json;

    /**
     * Call before doing any work.
     * empty            -> first time: go ahead and do the work
     * a StoredResponse -> already done: return this answer and do nothing else
     * throws           -> key reused for a different request (422), or still in progress (409)
     */
    public Optional<StoredResponse> begin(String merchantId, String key, String requestHash) {
        if (idempRepo.tryClaim(merchantId, key, requestHash)) {
            return Optional.empty();
        }

        KeyRecord existing = idempRepo.find(merchantId, key)
                .orElseThrow(() -> new IllegalStateException("Key " + key + " vanished after a conflict"));

        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException.IdempotencyKeyReusedException(key);
        }
        if (existing.state().equals("IN_PROGRESS")) {
            if (existing.leaseExpired() && idempRepo.takeOver(merchantId, key)) {
                return Optional.of(new StoredResponse(202, null));
            }
            throw new ApiException.IdempotencyInProgressException(key);
        }
        return Optional.of(new StoredResponse(existing.responseCode(), readBody(existing.responseBody())));
    }

    /**
     * Call at the end of the work, in the same transaction as the final change.
     */
    public void complete(String merchantId, String key, UUID paymentId, StoredResponse response) {
        idempRepo.complete(merchantId, key, paymentId, response.httpStatus(), writeBody(response.body()));
    }

    // IdempotencyService
    public void release(String merchantId, String key) {
        idempRepo.release(merchantId, key);
    }

    private String writeBody(PaymentResponse body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not save response", e);
        }
    }

    private PaymentResponse readBody(String body) {
        try {
            return json.readValue(body, PaymentResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read saved response", e);
        }
    }
}
