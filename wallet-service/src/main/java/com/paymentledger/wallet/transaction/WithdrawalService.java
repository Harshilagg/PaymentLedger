package com.paymentledger.wallet.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.dto.MoneyMapper;
import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.event.TransactionInitiatedEvent;
import com.paymentledger.wallet.event.TransactionType;
import com.paymentledger.wallet.outbox.OutboxEvent;
import com.paymentledger.wallet.outbox.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Unlike deposit, withdrawal is a debit: the funds are reserved (held) in the same local
 * transaction that validates and initiates the saga, so the check and the deduction from
 * available balance are atomic - this is what makes the no-overdraft guarantee hold under
 * concurrency instead of just in the single-request case. See SPEC.md "Balance reservation".
 *
 * Takes a walletId rather than a pre-loaded Wallet on purpose: OptimisticLockRetrier retries this
 * whole method on a lost @Version race, and a retry only re-reads the current version if the
 * lookup happens inside this transactional method - a Wallet instance loaded once by the caller
 * and reused across retries would keep retrying with the same stale version forever.
 */
@Service
public class WithdrawalService {

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final WalletRepository walletRepository;
    private final ObjectMapper objectMapper;

    public WithdrawalService(TransactionRepository transactionRepository,
                              OutboxEventRepository outboxEventRepository,
                              WalletRepository walletRepository,
                              ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.walletRepository = walletRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransactionResponse initiateWithdrawal(UUID walletId, BigDecimal amount, String idempotencyKey) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalStateException("Wallet " + walletId + " not found"));
        long amountMinor = MoneyMapper.toMinor(amount, wallet.getCurrency());

        wallet.reserve(amountMinor);
        walletRepository.save(wallet);

        Transaction transaction = Transaction.initiateWithdrawal(
                wallet.getId(), amountMinor, wallet.getCurrency(), idempotencyKey);
        transactionRepository.save(transaction);

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transaction.getId(), TransactionType.WITHDRAWAL,
                wallet.getId(), wallet.getAccountId(),
                null, null,
                amountMinor, wallet.getCurrency(),
                amountMinor, wallet.getCurrency());
        outboxEventRepository.save(new OutboxEvent(transaction.getId(), "TRANSACTION_INITIATED", writeJson(event)));

        return TransactionResponse.from(transaction);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox payload for " + value, e);
        }
    }
}
