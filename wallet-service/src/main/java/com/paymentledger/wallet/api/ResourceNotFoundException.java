package com.paymentledger.wallet.api;

/**
 * Thrown both when a resource genuinely does not exist and when it exists but belongs to someone
 * else - deliberately the same exception, so both produce the same 404. See SPEC.md "Error
 * handling" for why the two cases must be indistinguishable.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
