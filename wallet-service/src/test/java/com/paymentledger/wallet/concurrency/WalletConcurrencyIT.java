package com.paymentledger.wallet.concurrency;

import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.InsufficientFundsException;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.support.SharedPostgres;
import com.paymentledger.wallet.transaction.WithdrawalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The single test that proves the whole "ACID + optimistic locking + no overdraft" claim from
 * SPEC.md, rather than just asserting it in a README. Fires many concurrent withdrawal
 * initiations at the same wallet through the real WithdrawalService + OptimisticLockRetrier +
 * a real Postgres instance (Testcontainers) - mocked-repository unit tests cannot exercise an
 * actual @Version race, since there is no real database serializing concurrent writes to fake.
 *
 * Kafka is deliberately excluded: this test only needs to prove the reservation step never lets
 * the wallet go negative under concurrency, which is entirely a wallet-service + Postgres
 * question. Redelivery/outbox correctness has its own dedicated integration tests.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.datasource.hikari.maximum-pool-size=32",
        // This test measures contention on one wallet; a relay polling the same database every
        // 500ms is noise in exactly the thing being measured, and it has nothing to publish here
        // anyway since the KafkaTemplate below is a mock.
        "app.outbox.relay.poll-interval-ms=3600000",
        "app.idempotency.cleanup-interval-ms=3600000"
})
class WalletConcurrencyIT {

    /**
     * Kafka's autoconfiguration is excluded above, but OutboxRelay is still a normal @Component
     * that gets wired into the context and needs SOME KafkaTemplate bean to exist, or the whole
     * context fails to start. A harmless mock stands in - this test never asserts anything about
     * what gets published, only about the wallet-side reservation math.
     */
    @TestConfiguration
    static class NoKafkaConfig {
        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate() {
            KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
            when(template.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
            return template;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerProperties(registry);
    }

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private WithdrawalService withdrawalService;
    @Autowired
    private OptimisticLockRetrier retrier;

    @Test
    void concurrentWithdrawalsNeverDriveTheBalanceNegativeAndSettleExactlyRight() throws InterruptedException {
        Account account = accountRepository.save(new Account(UUID.randomUUID()));
        Wallet wallet = walletRepository.save(new Wallet(account.getId(), Currency.getInstance("USD")));
        walletRepository.save(fundWallet(wallet, 150_00)); // $150.00 in minor units

        int concurrentRequests = 20;
        long perRequestMinor = 10_00; // $10.00 each - at most 15 can ever succeed against $150

        ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();
        AtomicInteger contention = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        for (int i = 0; i < concurrentRequests; i++) {
            String idempotencyKey = "concurrency-test-" + i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    retrier.withRetry("test withdrawal", () ->
                            withdrawalService.initiateWithdrawal(wallet.getId(),
                                    new BigDecimal("10.00"), idempotencyKey));
                    succeeded.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficientFunds.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    // All 20 threads are released at the exact same instant on purpose, to put
                    // more contention on one row than any normal request would ever see. The
                    // retry budget is deliberately bounded (SPEC.md: "a small bounded retry
                    // count before giving up"), so exhausting it under this artificial extreme
                    // is an expected, safe outcome - the caller sees 503 and can retry the whole
                    // request - not a correctness failure. The actual invariant this test proves
                    // (a request can never succeed in reserving more than is available) holds
                    // regardless of how the unsuccessful ones fail.
                    contention.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown(); // release all threads at once to maximize real contention
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(finished).as("all withdrawal attempts completed within the timeout").isTrue();
        // Any IllegalStateException from Wallet.assertInvariants() would show up here - a failure
        // of this assertion is exactly what "the balance went negative" looks like in this test.
        assertThat(unexpected).as("no unexpected exceptions, in particular no invariant violations").isEmpty();

        assertThat(succeeded.get() + insufficientFunds.get() + contention.get())
                .as("every request resolved to exactly one outcome - none silently lost")
                .isEqualTo(concurrentRequests);
        // The actual safety guarantee: never more successful reservations than the wallet can
        // afford, no matter how the other 5 requests' failures are distributed between "cleanly
        // rejected" and "gave up under contention".
        assertThat(succeeded.get()).as("never over-reserves").isLessThanOrEqualTo(15);
        assertThat(insufficientFunds.get() + contention.get()).isGreaterThanOrEqualTo(5);

        Wallet finalState = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(finalState.getBalanceMinor()).isEqualTo(150_00);
        assertThat(finalState.getReservedMinor()).isEqualTo(succeeded.get() * perRequestMinor);
        assertThat(finalState.availableMinor()).isEqualTo(150_00 - succeeded.get() * perRequestMinor);
    }

    private Wallet fundWallet(Wallet wallet, long amountMinor) {
        wallet.credit(amountMinor);
        return wallet;
    }
}
