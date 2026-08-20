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
        long fromAmount = event.fromAmountMinor();
        String fromCurrency = event.fromCurrency();
        long toAmount = event.toAmountMinor();
        String toCurrency = event.toCurrency();

        return switch (event.transactionType()) {
            case DEPOSIT -> {
                requireToWallet(event);
                UUID clearingWalletId = ExternalClearingAccount.walletIdFor(toCurrency);
                UUID clearingAccountId = ExternalClearingAccount.accountIdFor(toCurrency);
                yield List.of(
                        new LedgerEntry(transactionId, event.toWalletId(), event.toAccountId(),
                                Direction.CREDIT, toAmount, toCurrency),
                        new LedgerEntry(transactionId, clearingWalletId, clearingAccountId,
                                Direction.DEBIT, toAmount, toCurrency));
            }
            case WITHDRAWAL -> {
                requireFromWallet(event);
                UUID clearingWalletId = ExternalClearingAccount.walletIdFor(fromCurrency);
                UUID clearingAccountId = ExternalClearingAccount.accountIdFor(fromCurrency);
                yield List.of(
                        new LedgerEntry(transactionId, event.fromWalletId(), event.fromAccountId(),
                                Direction.DEBIT, fromAmount, fromCurrency),
                        new LedgerEntry(transactionId, clearingWalletId, clearingAccountId,
                                Direction.CREDIT, fromAmount, fromCurrency));
            }
            case TRANSFER -> {
                requireFromWallet(event);
                requireToWallet(event);
                yield fromCurrency.equals(toCurrency)
                        ? List.of(
                                new LedgerEntry(transactionId, event.fromWalletId(), event.fromAccountId(),
                                        Direction.DEBIT, fromAmount, fromCurrency),
                                new LedgerEntry(transactionId, event.toWalletId(), event.toAccountId(),
                                        Direction.CREDIT, toAmount, toCurrency))
                        : crossCurrencyTransferEntries(transactionId, event, fromAmount, fromCurrency, toAmount, toCurrency);
            }
        };
    }

    /**
     * A LedgerEntry never mixes currencies, so a cross-currency transfer needs two clearing legs
     * (one per currency) instead of one: debit source, credit the fromCurrency clearing account
     * (balances the fromCurrency side), debit the toCurrency clearing account, credit destination
     * (balances the toCurrency side) - see SPEC.md's cross-currency data model.
     */
    private List<LedgerEntry> crossCurrencyTransferEntries(UUID transactionId, TransactionInitiatedEvent event,
                                                             long fromAmount, String fromCurrency,
                                                             long toAmount, String toCurrency) {
        return List.of(
                new LedgerEntry(transactionId, event.fromWalletId(), event.fromAccountId(),
                        Direction.DEBIT, fromAmount, fromCurrency),
                new LedgerEntry(transactionId, ExternalClearingAccount.walletIdFor(fromCurrency),
                        ExternalClearingAccount.accountIdFor(fromCurrency), Direction.CREDIT, fromAmount, fromCurrency),
                new LedgerEntry(transactionId, ExternalClearingAccount.walletIdFor(toCurrency),
                        ExternalClearingAccount.accountIdFor(toCurrency), Direction.DEBIT, toAmount, toCurrency),
                new LedgerEntry(transactionId, event.toWalletId(), event.toAccountId(),
                        Direction.CREDIT, toAmount, toCurrency));
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
