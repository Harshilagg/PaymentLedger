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
 * Branches on which of fromWalletId/toWalletId are set rather than on Transaction.type: a debit
 * leg (settle a hold, or release it on failure) exists whenever fromWalletId is set, a credit leg
 * whenever toWalletId is set. This is exactly true for DEPOSIT (to only), WITHDRAWAL (from only)
 * and TRANSFER (both) - and it is exactly as true for REVERSAL, whose fromWalletId/toWalletId are
 * already the reversed direction by the time ReversalService builds it. No separate REVERSAL case
 * needed.
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
        if (transaction.getFromWalletId() != null) {
            settle(transaction.getFromWalletId(), transaction.getAmountMinor());
        }
        if (transaction.getToWalletId() != null) {
            // toLegAmountMinor() is amountMinor for a same-currency transaction and the
            // separately-converted amount for cross-currency - see Transaction.toLegAmountMinor().
            credit(transaction.getToWalletId(), transaction.toLegAmountMinor());
        }
        transaction.markCompleted();
        transactionRepository.save(transaction);
    }

    private void applyFailed(Transaction transaction, String reason) {
        // A credit leg never took a hold (crediting can't overdraft), so there is nothing to
        // release for it - only a debit leg (fromWalletId set) ever reserved anything.
        if (transaction.getFromWalletId() != null) {
            releaseHold(transaction.getFromWalletId(), transaction.getAmountMinor());
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
