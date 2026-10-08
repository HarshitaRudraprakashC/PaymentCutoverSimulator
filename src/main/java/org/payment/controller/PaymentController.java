package org.payment.controller;

import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.payment.Payment;
import org.payment.model.payment.PaymentResponse;
import org.payment.model.payment.PaymentStatus;
import org.payment.model.request.AuthRequest;
import org.payment.service.PaymentService;
import org.springframework.http.HttpStatus;
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
    @PostMapping("/authorize")
    public ResponseEntity<PaymentResponse> authorize(
            @Valid @RequestBody AuthRequest authRequest,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey){

        Payment payment = paymentService.authorize(authRequest, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentResponse.from(payment));
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
        Payment payment = paymentService.capture(id, idempotencyKey);
        HttpStatus status = payment.status() == PaymentStatus.CAPTURE_PENDING
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(PaymentResponse.from(payment));
    }


    /** Read a payment's current state. No idempotency key needed: reading changes nothing. */
    @GetMapping("/{id}")
    public PaymentResponse get(@NonNull @PathVariable UUID id){
        return PaymentResponse.from(paymentService.get(id));
    }

}
