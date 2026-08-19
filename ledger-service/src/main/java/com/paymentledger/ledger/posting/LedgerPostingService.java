package com.paymentledger.ledger.posting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.ledger.domain.Direction;
import com.paymentledger.ledger.domain.ExternalClearingAccount;
import com.paymentledger.ledger.domain.LedgerEntry;
import com.paymentledger.ledger.domain.LedgerEntryRepository;
import com.paymentledger.ledger.event.OutcomeStatus;
import com.paymentledger.ledger.event.TransactionInitiatedEvent;
import com.paymentledger.ledger.event.TransactionOutcomeEvent;
import com.paymentledger.ledger.outbox.OutboxEvent;
import com.paymentledger.ledger.outbox.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Owns the one decision this service exists to make: what ledger_entry rows a given transaction
 * produces, and whether it can be posted at all. Idempotent per SPEC.md - a transaction already
 * posted is a silent no-op, which is what makes Kafka redelivery safe.
 */
@Service
public class LedgerPostingService {

    private static final Logger log = LoggerFactory.getLogger(LedgerPostingService.class);

    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public LedgerPostingService(LedgerEntryRepository ledgerEntryRepository,
                                 OutboxEventRepository outboxEventRepository,
                                 ObjectMapper objectMapper) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void post(TransactionInitiatedEvent event) {
        if (ledgerEntryRepository.existsByTransactionId(event.transactionId())) {
            log.info("Transaction {} is already posted - redelivery no-op", event.transactionId());
            return;
        }

        try {
            List<LedgerEntry> entries = buildEntries(event);
            ledgerEntryRepository.saveAll(entries);
            recordOutcome(event.transactionId(), OutcomeStatus.POSTED, null);
        } catch (IllegalArgumentException rejected) {
            recordOutcome(event.transactionId(), OutcomeStatus.FAILED, rejected.getMessage());
        }
    }

    private List<LedgerEntry> buildEntries(TransactionInitiatedEvent event) {
        UUID transactionId = event.transactionId();
        long amount = event.amountMinor();
        String currency = event.currency();
        UUID clearingWalletId = ExternalClearingAccount.walletIdFor(currency);
        UUID clearingAccountId = ExternalClearingAccount.accountIdFor(currency);

        return switch (event.transactionType()) {
            case DEPOSIT -> {
                requireToWallet(event);
                yield List.of(
                        new LedgerEntry(transactionId, event.toWalletId(), event.toAccountId(),
                                Direction.CREDIT, amount, currency),
                        new LedgerEntry(transactionId, clearingWalletId, clearingAccountId,
                                Direction.DEBIT, amount, currency));
            }
            case WITHDRAWAL -> {
                requireFromWallet(event);
                yield List.of(
                        new LedgerEntry(transactionId, event.fromWalletId(), event.fromAccountId(),
                                Direction.DEBIT, amount, currency),
                        new LedgerEntry(transactionId, clearingWalletId, clearingAccountId,
                                Direction.CREDIT, amount, currency));
            }
            case TRANSFER -> {
                requireFromWallet(event);
                requireToWallet(event);
                yield List.of(
                        new LedgerEntry(transactionId, event.fromWalletId(), event.fromAccountId(),
                                Direction.DEBIT, amount, currency),
                        new LedgerEntry(transactionId, event.toWalletId(), event.toAccountId(),
                                Direction.CREDIT, amount, currency));
            }
        };
    }

    private void requireFromWallet(TransactionInitiatedEvent event) {
        if (event.fromWalletId() == null || event.fromAccountId() == null) {
            throw new IllegalArgumentException(
                    event.transactionType() + " requires fromWalletId and fromAccountId");
        }
    }

    private void requireToWallet(TransactionInitiatedEvent event) {
        if (event.toWalletId() == null || event.toAccountId() == null) {
            throw new IllegalArgumentException(
                    event.transactionType() + " requires toWalletId and toAccountId");
        }
    }

    private void recordOutcome(UUID transactionId, OutcomeStatus status, String reason) {
        TransactionOutcomeEvent outcome = new TransactionOutcomeEvent(transactionId, status, reason);
        outboxEventRepository.save(new OutboxEvent(transactionId, "TRANSACTION_" + status,
                writeJson(outcome)));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + value, e);
        }
    }
}
