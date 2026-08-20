package com.paymentledger.wallet.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.dto.MoneyMapper;
import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.event.TransactionInitiatedEvent;
import com.paymentledger.wallet.event.TransactionType;
import com.paymentledger.wallet.outbox.OutboxEvent;
import com.paymentledger.wallet.outbox.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Deposit is the simplest saga: single wallet, credit-only, no reservation needed since crediting
 * can't overdraft (see SPEC.md "Balance reservation"). Initiation writes the Transaction and the
 * outbox row in one local transaction; the actual balance credit happens later, when
 * TransactionOutcomeListener consumes the POSTED outcome from ledger-service.
 */
@Service
public class DepositService {

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public DepositService(TransactionRepository transactionRepository,
                           OutboxEventRepository outboxEventRepository,
                           ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransactionResponse initiateDeposit(Wallet wallet, BigDecimal amount, String idempotencyKey) {
        long amountMinor = MoneyMapper.toMinor(amount, wallet.getCurrency());

        Transaction transaction = Transaction.initiateDeposit(
                wallet.getId(), amountMinor, wallet.getCurrency(), idempotencyKey);
        transactionRepository.save(transaction);

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transaction.getId(), TransactionType.DEPOSIT,
                null, null,
                wallet.getId(), wallet.getAccountId(),
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
