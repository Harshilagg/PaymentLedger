package com.paymentledger.wallet.transaction;

import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.event.OutcomeStatus;
import com.paymentledger.wallet.event.TransactionOutcomeEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Applies a ledger-service outcome to wallet balances. The isPending() guard is what makes
 * redelivery of transaction-outcome safe: a transaction already COMPLETED or FAILED is a no-op,
 * so a duplicate POSTED can't double-settle and a duplicate FAILED can't release the same hold
 * twice - see SPEC.md "Idempotent event consumption".
 *
 * REVERSAL settlement and the optimistic-lock retry loop are both deliberately not here yet -
 * they land with the Reversal flow and the concurrency test suite respectively, later in the
 * build order.
 */
@Service
public class SettlementService {

    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;

    public SettlementService(TransactionRepository transactionRepository, WalletRepository walletRepository) {
        this.transactionRepository = transactionRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional
    public void applyOutcome(TransactionOutcomeEvent event) {
        Transaction transaction = transactionRepository.findById(event.transactionId())
                .orElseThrow(() -> new IllegalStateException(
                        "No transaction found for outcome " + event.transactionId()));

        if (!transaction.isPending()) {
            return;
        }

        if (event.status() == OutcomeStatus.POSTED) {
            applyPosted(transaction);
        } else {
            applyFailed(transaction, event.reason());
        }
    }

    private void applyPosted(Transaction transaction) {
        switch (transaction.getType()) {
            case DEPOSIT -> credit(transaction.getToWalletId(), transaction.getAmountMinor());
            case WITHDRAWAL -> settle(transaction.getFromWalletId(), transaction.getAmountMinor());
            case TRANSFER -> {
                settle(transaction.getFromWalletId(), transaction.getAmountMinor());
                credit(transaction.getToWalletId(), transaction.getAmountMinor());
            }
            case REVERSAL -> throw new IllegalStateException("REVERSAL settlement is not implemented yet");
        }
        transaction.markCompleted();
        transactionRepository.save(transaction);
    }

    private void applyFailed(Transaction transaction, String reason) {
        switch (transaction.getType()) {
            case DEPOSIT -> {
                // No hold was ever taken for a deposit (crediting can't overdraft), so there is
                // nothing to release.
            }
            case WITHDRAWAL, TRANSFER -> releaseHold(transaction.getFromWalletId(), transaction.getAmountMinor());
            case REVERSAL -> throw new IllegalStateException("REVERSAL settlement is not implemented yet");
        }
        transaction.markCompensating(reason);
        transaction.markFailed(reason);
        transactionRepository.save(transaction);
    }

    private void credit(UUID walletId, long amountMinor) {
        Wallet wallet = loadWallet(walletId);
        wallet.credit(amountMinor);
        walletRepository.save(wallet);
    }

    private void settle(UUID walletId, long amountMinor) {
        Wallet wallet = loadWallet(walletId);
        wallet.settleReservedDebit(amountMinor);
        walletRepository.save(wallet);
    }

    private void releaseHold(UUID walletId, long amountMinor) {
        Wallet wallet = loadWallet(walletId);
        wallet.releaseHold(amountMinor);
        walletRepository.save(wallet);
    }

    private Wallet loadWallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalStateException("Wallet " + walletId + " not found during settlement"));
    }
}
