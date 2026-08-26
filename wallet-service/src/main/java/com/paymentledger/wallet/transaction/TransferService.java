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
import com.paymentledger.wallet.fx.FxConverter;
import com.paymentledger.wallet.outbox.OutboxEvent;
import com.paymentledger.wallet.outbox.OutboxEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.paymentledger.wallet.api.ResourceNotFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The full saga from SPEC.md: source wallet is the debit leg (reserved atomically with
 * initiation, exactly like withdrawal), destination is credited later on settlement. Only the
 * source wallet's ownership is checked here - transfers move money to someone else's wallet by
 * design, the destination just needs to exist.
 *
 * Cross-currency: the source is always reserved and debited in its own currency (that's what
 * "available balance" means); FxConverter computes what the destination receives in its own
 * currency at initiation time, so both amounts are fixed and sent to ledger-service together -
 * there is no second conversion step at settlement.
 *
 * Takes fromWalletId rather than a pre-loaded Wallet for the same reason as WithdrawalService:
 * OptimisticLockRetrier retries this whole method, and the retry only helps if the lookup happens
 * inside this transactional method so each attempt re-reads the current @Version.
 */
@Service
public class TransferService {

    private final TransactionRepository transactionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final WalletRepository walletRepository;
    private final FxConverter fxConverter;
    private final ObjectMapper objectMapper;

    public TransferService(TransactionRepository transactionRepository,
                            OutboxEventRepository outboxEventRepository,
                            WalletRepository walletRepository,
                            FxConverter fxConverter,
                            ObjectMapper objectMapper) {
        this.transactionRepository = transactionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.walletRepository = walletRepository;
        this.fxConverter = fxConverter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransactionResponse initiateTransfer(UUID fromWalletId, UUID toWalletId, BigDecimal amount,
                                                 String idempotencyKey) {
        if (toWalletId.equals(fromWalletId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot transfer a wallet to itself");
        }

        Wallet fromWallet = walletRepository.findById(fromWalletId)
                .orElseThrow(() -> new IllegalStateException("Wallet " + fromWalletId + " not found"));

        Wallet toWallet = walletRepository.findById(toWalletId)
                .orElseThrow(() -> new ResourceNotFoundException("Destination wallet not found"));

        long fromAmountMinor = MoneyMapper.toMinor(amount, fromWallet.getCurrency());
        long toAmountMinor = fxConverter.convert(fromAmountMinor, fromWallet.getCurrency(), toWallet.getCurrency());

        fromWallet.reserve(fromAmountMinor);
        walletRepository.save(fromWallet);

        Transaction transaction = Transaction.initiateTransfer(
                fromWallet.getId(), toWallet.getId(),
                fromAmountMinor, fromWallet.getCurrency(),
                toAmountMinor, toWallet.getCurrency(),
                idempotencyKey);
        transactionRepository.save(transaction);

        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transaction.getId(), TransactionType.TRANSFER,
                fromWallet.getId(), fromWallet.getAccountId(),
                toWallet.getId(), toWallet.getAccountId(),
                fromAmountMinor, fromWallet.getCurrency(),
                toAmountMinor, toWallet.getCurrency());
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
