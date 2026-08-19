package com.paymentledger.wallet.idempotency;

public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key " + key + " was already used with a different request body");
    }
}
