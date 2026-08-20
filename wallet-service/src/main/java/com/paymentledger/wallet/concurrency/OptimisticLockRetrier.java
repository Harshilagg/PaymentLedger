package com.paymentledger.wallet.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Retries an operation that lost an optimistic-lock race on a wallet's @Version column - see
 * SPEC.md "Optimistic locking, precisely". Each retry re-invokes the whole Supplier, not just a
 * wallet save: the Supplier is expected to be (or wrap) a @Transactional method boundary, so a
 * failed attempt rolls back completely and the next attempt starts a fresh transaction that
 * re-reads the wallet at its current version - there is no half-applied state to reconcile.
 */
@Component
public class OptimisticLockRetrier {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetrier.class);

    private final int maxAttempts;

    public OptimisticLockRetrier(@Value("${app.optimistic-lock.max-retries}") int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public <T> T withRetry(String description, Supplier<T> operation) {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return operation.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    log.warn("Exhausted {} optimistic-lock retries for {}", maxAttempts, description);
                    throw e;
                }
                log.debug("Optimistic lock conflict for {} (attempt {}/{}), retrying",
                        description, attempt, maxAttempts);
            }
        }
    }
}
