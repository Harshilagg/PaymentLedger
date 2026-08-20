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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The full saga from SPEC.md: source wallet is the debit leg (reserved atomically with
 * initiation, exactly like withdrawal), destination is credited later on settlement. Only the
 * source wallet's ownership is checked here - transfers move money to someone else's wallet by
 * design, the destination just needs to exist. Cross-currency transfers are deferred to a later
 * build step (they need the FX clearing account and a rate lookup); for now source and
 * destination currency must match.
 */
@Service
public class TransferService {

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final WalletRepository walletRepository;
    private final ObjectMapper objectMapper;

    public TransferService(TransactionRepository transactionRepository,
                            OutboxEventRepository outboxEventRepository,
                            WalletRepository walletRepository,
                            ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.walletRepository = walletRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransactionResponse initiateTransfer(Wallet fromWallet, UUID toWalletId, BigDecimal amount,
                                                 String idempotencyKey) {
        if (toWalletId.equals(fromWallet.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot transfer a wallet to itself");
        }

        Wallet toWallet = walletRepository.findById(toWalletId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Destination wallet not found"));

        if (!toWallet.getCurrency().equals(fromWallet.getCurrency())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cross-currency transfers are not supported yet");
        }

        long amountMinor = MoneyMapper.toMinor(amount, fromWallet.getCurrency());

        fromWallet.reserve(amountMinor);
        walletRepository.save(fromWallet);

        Transaction transaction = Transaction.initiateTransfer(
                fromWallet.getId(), toWallet.getId(), amountMinor, fromWallet.getCurrency(), idempotencyKey);
        transactionRepository.save(transaction);

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transaction.getId(), TransactionType.TRANSFER,
                fromWallet.getId(), fromWallet.getAccountId(),
                toWallet.getId(), toWallet.getAccountId(),
                amountMinor, fromWallet.getCurrency());
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
