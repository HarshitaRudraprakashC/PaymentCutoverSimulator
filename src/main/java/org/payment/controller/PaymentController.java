package org.payment.controller;

import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.idempotency.StoredResponse;
import org.payment.model.payment.PaymentResponse;
import org.payment.model.request.AuthRequest;
import org.payment.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
@Slf4j
@RequiredArgsConstructor
public class PaymentController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private final PaymentService paymentService;

    /** Authorize a payment. 201 Created with the new payment. */
    @PostMapping()
    public ResponseEntity<PaymentResponse> authorize(
            @Valid @RequestBody AuthRequest authRequest,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey){

        return toHttp(paymentService.authorize(idempotencyKey, authRequest));
    }


    /*
    Capture a payment.
     *   200 OK        -> CAPTURED: the money moved, exactly once
     *   202 Accepted  -> CAPTURE_PENDING: we asked, the answer isn't known yet; retry with the same key
     * */
    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentResponse> capture(
            @NonNull @PathVariable UUID id,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey ){
        return toHttp(paymentService.capture(id, idempotencyKey));
    }


    /** Read a payment's current state. No idempotency key needed: reading changes nothing. */
    @GetMapping("/{id}")
    public PaymentResponse get(@NonNull @PathVariable UUID id){
        return PaymentResponse.from(paymentService.get(id));
    }

    /** Same status and body every time, whether it's the first answer or a replay. */
    private static ResponseEntity<PaymentResponse> toHttp(StoredResponse response) {
        return ResponseEntity.status(response.httpStatus()).body(response.body());
    }

}
