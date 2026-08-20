package com.paymentledger.wallet.concurrency;

import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.InsufficientFundsException;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.transaction.WithdrawalService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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
@Testcontainers
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "spring.datasource.hikari.maximum-pool-size=32"
})
class WalletConcurrencyIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
        long perRequestMinor = 10_00; // $10.00 each - exactly 15 can succeed against $150

        ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficientFunds = new AtomicInteger();
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

        assertThat(succeeded.get()).isEqualTo(15);
        assertThat(insufficientFunds.get()).isEqualTo(5);

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
