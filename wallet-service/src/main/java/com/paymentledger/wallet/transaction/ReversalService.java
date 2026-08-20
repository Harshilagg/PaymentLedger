package com.paymentledger.wallet.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.TransactionStatus;
import com.paymentledger.wallet.domain.TransactionType;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.event.TransactionInitiatedEvent;
import com.paymentledger.wallet.outbox.OutboxEvent;
import com.paymentledger.wallet.outbox.OutboxEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * A reversal never mutates or deletes the original transaction or its ledger entries - it posts
 * a new transaction whose legs are exactly the original's, swapped (see SPEC.md's Reversal flow).
 * The swap is computed once here; from that point on it is initiated and settled through the
 * exact same reservation/settlement machinery as any other debit/credit shape, since
 * WithdrawalService/TransferService/SettlementService already only care about which of
 * fromWalletId/toWalletId are set, not the transaction's type label.
 */
@Service
public class ReversalService {

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final WalletRepository walletRepository;
    private final ObjectMapper objectMapper;

    public ReversalService(TransactionRepository transactionRepository,
                            OutboxEventRepository outboxEventRepository,
                            WalletRepository walletRepository,
                            ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.walletRepository = walletRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransactionResponse initiateReversal(UUID originalTransactionId, String idempotencyKey) {
        Transaction original = transactionRepository.findById(originalTransactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));

        if (original.getStatus() != TransactionStatus.COMPLETED) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only a COMPLETED transaction can be reversed");
        }
        if (original.getType() == TransactionType.REVERSAL) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Cannot reverse a reversal");
        }
        if (transactionRepository.existsByOriginalTransactionIdAndStatusNot(
                originalTransactionId, TransactionStatus.FAILED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Transaction has already been reversed");
        }

        // Whatever was credited becomes the debit leg and vice versa.
        UUID reversedFromWalletId = original.getToWalletId();
        UUID reversedToWalletId = original.getFromWalletId();
        long amountMinor = original.getAmountMinor();
        String currency = original.getCurrency();

        UUID reversedFromAccountId = null;
        if (reversedFromWalletId != null) {
            Wallet fromWallet = walletRepository.findById(reversedFromWalletId)
                    .orElseThrow(() -> new IllegalStateException("Wallet " + reversedFromWalletId + " not found"));
            fromWallet.reserve(amountMinor);
            walletRepository.save(fromWallet);
            reversedFromAccountId = fromWallet.getAccountId();
        }

        UUID reversedToAccountId = null;
        if (reversedToWalletId != null) {
            Wallet toWallet = walletRepository.findById(reversedToWalletId)
                    .orElseThrow(() -> new IllegalStateException("Wallet " + reversedToWalletId + " not found"));
            reversedToAccountId = toWallet.getAccountId();
        }

        Transaction reversal = Transaction.initiateReversal(reversedFromWalletId, reversedToWalletId,
                amountMinor, currency, idempotencyKey, originalTransactionId);
        transactionRepository.save(reversal);

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                reversal.getId(), wireTypeFor(reversedFromWalletId, reversedToWalletId),
                reversedFromWalletId, reversedFromAccountId,
                reversedToWalletId, reversedToAccountId,
                amountMinor, currency);
        outboxEventRepository.save(new OutboxEvent(reversal.getId(), "TRANSACTION_INITIATED", writeJson(event)));

        return TransactionResponse.from(reversal);
    }

    /** ledger-service only knows DEPOSIT/WITHDRAWAL/TRANSFER shapes - REVERSAL is a wallet-service-only label. */
    private com.paymentledger.wallet.event.TransactionType wireTypeFor(UUID fromWalletId, UUID toWalletId) {
        if (fromWalletId != null && toWalletId != null) {
            return com.paymentledger.wallet.event.TransactionType.TRANSFER;
        }
        return fromWalletId != null
                ? com.paymentledger.wallet.event.TransactionType.WITHDRAWAL
                : com.paymentledger.wallet.event.TransactionType.DEPOSIT;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + value, e);
        }
    }
}
