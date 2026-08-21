package com.paymentledger.wallet.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.event.OutcomeStatus;
import com.paymentledger.wallet.event.TransactionOutcomeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The settlement-side counterpart to ledger-service's TransactionInitiatedRedeliveryIT: proves
 * that redelivering transaction-outcome cannot double-settle a wallet, backed by the real
 * Transaction.isPending() guard racing against real Kafka redelivery and a real Postgres row -
 * not a mock that can't actually deliver the same message twice concurrently. See SPEC.md
 * "Idempotent event consumption" (the settlement-path half, which the unique constraint on
 * ledger_entry does NOT cover since settlement is an UPDATE, not an INSERT).
 */
@Testcontainers
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=10")
class TransactionOutcomeRedeliveryIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    // org.testcontainers.containers.KafkaContainer (the older class) is hard-wired to Confluent's
    // image layout (it looks for zookeeper-server-start and /etc/confluent/docker/run inside the
    // container) and breaks even when told the image is "compatible". This newer
    // org.testcontainers.kafka.KafkaContainer is the one actually built for the official
    // apache/kafka image's own KRaft-mode startup.
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    @BeforeAll
    static void startContainers() {
        POSTGRES.start();
        KAFKA.start();
    }

    @AfterAll
    static void stopContainers() {
        KAFKA.stop();
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Value("${app.kafka.topics.transaction-outcome}")
    private String topic;

    @Test
    void redeliveredOutcomeSettlesTheWalletExactlyOnce() throws Exception {
        Account account = accountRepository.save(new Account(UUID.randomUUID()));
        Wallet wallet = walletRepository.save(new Wallet(account.getId(), Currency.getInstance("USD")));
        Transaction transaction = transactionRepository.save(
                Transaction.initiateDeposit(wallet.getId(), 5_000, "USD", "redelivery-test-key"));

        TransactionOutcomeEvent event = new TransactionOutcomeEvent(transaction.getId(), OutcomeStatus.POSTED, null);
        String payload = objectMapper.writeValueAsString(event);

        kafkaTemplate.send(topic, transaction.getId().toString(), payload).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(topic, transaction.getId().toString(), payload).get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Wallet current = walletRepository.findById(wallet.getId()).orElseThrow();
            assertThat(current.getBalanceMinor()).isEqualTo(5_000);
        });

        // Same defensive wait as the ledger-service redelivery test - give a wrongly-applied
        // second settlement time to land before asserting the balance is still exactly right.
        Thread.sleep(3_000);
        Wallet finalState = walletRepository.findById(wallet.getId()).orElseThrow();
        assertThat(finalState.getBalanceMinor()).isEqualTo(5_000);

        Transaction finalTransaction = transactionRepository.findById(transaction.getId()).orElseThrow();
        assertThat(finalTransaction.getStatus().name()).isEqualTo("COMPLETED");
    }
}
