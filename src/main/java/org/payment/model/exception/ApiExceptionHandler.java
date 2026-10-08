package org.payment.model.exception;

import org.payment.model.exception.ApiException.GateBlockedException;
import org.payment.model.exception.ApiException.IdempotencyInProgressException;
import org.payment.model.exception.ApiException.IdempotencyKeyReusedException;
import org.payment.model.exception.ApiException.InvalidStateException;
import org.payment.model.exception.ApiException.PaymentNotFoundException;
import org.payment.model.response.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException e) {
        return error(HttpStatus.BAD_REQUEST, "MISSING_HEADER", e.getHeaderName() + " header is required");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidBody(MethodArgumentNotValidException e) {
        String details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", details);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(PaymentNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(IdempotencyInProgressException.class)
    ResponseEntity<ErrorResponse> inProgress(IdempotencyInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(new ErrorResponse("REQUEST_IN_PROGRESS", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ErrorResponse> keyReused(IdempotencyKeyReusedException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(InvalidStateException.class)
    ResponseEntity<ErrorResponse> invalidState(InvalidStateException e) {
        return error(HttpStatus.CONFLICT, "INVALID_STATE", e.getMessage());
    }

    @ExceptionHandler(GateBlockedException.class)
    ResponseEntity<ErrorResponse> gateBlocked(GateBlockedException e) {
        return error(HttpStatus.CONFLICT, "GATE_BLOCKED", e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}
