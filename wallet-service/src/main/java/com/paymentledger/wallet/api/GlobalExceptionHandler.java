package com.paymentledger.wallet.api;

import com.paymentledger.wallet.domain.InsufficientFundsException;
import com.paymentledger.wallet.idempotency.IdempotencyConflictException;
import com.paymentledger.wallet.security.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> handleIdempotencyConflict(IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    // The message is the exception's own fixed text, never anything derived from the attempt -
    // see InvalidCredentialsException for why every rejection has to look identical.
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientFunds(InsufficientFundsException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("message", e.getMessage()));
    }

    // Only reached once OptimisticLockRetrier has exhausted its retries - see SPEC.md
    // "Optimistic locking, precisely". 503 tells the client this is transient contention, not a
    // request-shape problem, so retrying the whole request later is the right response.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleExhaustedOptimisticLockRetries(
            ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "High contention on this wallet - please retry"));
    }
}
