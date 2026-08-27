package com.paymentledger.ledger.posting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.ledger.domain.LedgerEntry;
import com.paymentledger.ledger.domain.LedgerEntryRepository;
import com.paymentledger.ledger.event.TransactionInitiatedEvent;
import com.paymentledger.ledger.event.TransactionType;
import com.paymentledger.ledger.support.SharedKafka;
import com.paymentledger.ledger.support.SharedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the redelivery-safety claim from SPEC.md's "Idempotent event consumption" section against
 * real infrastructure: publishes the SAME transaction-initiated event twice (simulating Kafka's
 * at-least-once delivery after an outbox-relay crash-before-ack) and asserts only one pair of
 * ledger_entry rows ever exists for that transaction, backed by the real unique constraint on
 * (transaction_id, wallet_id, direction) - not a mock that can't actually race two inserts.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=10")
class TransactionInitiatedRedeliveryIT {


    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        SharedPostgres.registerProperties(registry);
        SharedKafka.registerProperties(registry);
    }

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Value("${app.kafka.topics.transaction-initiated}")
    private String topic;

    @Test
    void redeliveredEventProducesExactlyOnePairOfLedgerRows() throws Exception {
        UUID transactionId = UUID.randomUUID();
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transactionId, TransactionType.TRANSFER,
                UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                5_000, "USD", 5_000, "USD");
        String payload = objectMapper.writeValueAsString(event);

        kafkaTemplate.send(topic, transactionId.toString(), payload).get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(topic, transactionId.toString(), payload).get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionId(transactionId);
            assertThat(entries).hasSize(2);
        });

        // The first assertion could pass on the way to a duplicate landing a beat later - give
        // any wrongly-duplicated insert time to show up before asserting the count is still 2.
        Thread.sleep(3_000);
        assertThat(ledgerEntryRepository.findByTransactionId(transactionId)).hasSize(2);
    }
}
