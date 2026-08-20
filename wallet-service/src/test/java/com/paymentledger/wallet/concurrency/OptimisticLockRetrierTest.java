package com.paymentledger.wallet.concurrency;

import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimisticLockRetrierTest {

    @Test
    void succeedsWithoutRetryingWhenTheFirstAttemptWorks() {
        OptimisticLockRetrier retrier = new OptimisticLockRetrier(5);
        AtomicInteger attempts = new AtomicInteger();

        String result = retrier.withRetry("test", () -> {
            attempts.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void retriesUntilSuccessWithinTheBudget() {
        OptimisticLockRetrier retrier = new OptimisticLockRetrier(5);
        AtomicInteger attempts = new AtomicInteger();

        String result = retrier.withRetry("test", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new ObjectOptimisticLockingFailureException("Wallet", "id");
            }
            return "ok-after-retries";
        });

        assertThat(result).isEqualTo("ok-after-retries");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void rethrowsAfterExhaustingTheRetryBudget() {
        OptimisticLockRetrier retrier = new OptimisticLockRetrier(3);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retrier.withRetry("test", () -> {
            attempts.incrementAndGet();
            throw new ObjectOptimisticLockingFailureException("Wallet", "id");
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(attempts.get()).isEqualTo(3);
    }
}
