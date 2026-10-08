package org.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.payment.model.payment.Payment;
import org.payment.model.request.AuthRequest;
import org.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {

//    private final PaymentRepository paymentRepository;


    public Payment authorize(AuthRequest authRequest, String idempotencyKey) {
        return null;
    }

    public Payment capture(UUID id, String idempotencyKey) {
        return null;
    }

    public Payment get(UUID id) {
        return null;
    }
}
