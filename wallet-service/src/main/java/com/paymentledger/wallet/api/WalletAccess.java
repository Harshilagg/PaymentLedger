package com.paymentledger.wallet.api;

import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Shared load-and-ownership-check used by every controller that operates on a wallet directly. */
@Component
public class WalletAccess {

    private final WalletRepository walletRepository;
    private final AccountRepository accountRepository;

    public WalletAccess(WalletRepository walletRepository, AccountRepository accountRepository) {
        this.walletRepository = walletRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * "No such wallet" and "someone else's wallet" both raise the same exception with the same
     * message. Anything that distinguished them would answer the question "is this id real?" for
     * an id the caller has no business knowing about - see SPEC.md "Error handling".
     */
    public Wallet loadOwnedWallet(UUID walletId) {
        Optional<Wallet> wallet = walletRepository.findById(walletId);
        boolean owned = wallet
                .map(Wallet::getAccountId)
                .flatMap(accountRepository::findById)
                .filter(AccountController::isOwner)
                .isPresent();

        if (!owned) {
            throw new ResourceNotFoundException("Wallet " + walletId + " not found");
        }
        return wallet.orElseThrow();
    }

    /** Non-throwing check, for endpoints (like reversals) where the caller may own either of two wallets. */
    public boolean isOwnedWallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .map(Wallet::getAccountId)
                .flatMap(accountRepository::findById)
                .map(account -> account.getOwnerId().equals(CurrentUser.ownerId()))
                .orElse(false);
    }

    /**
     * A transaction's two legs can belong to different owners (e.g. a transfer) - being a party
     * to either side is enough to read it. Used by ReversalController and TransactionController.
     * Takes the whole transaction so the rejection is worded identically to the one a caller gets
     * for a transaction id that does not exist at all.
     */
    public void requireParty(Transaction transaction) {
        UUID fromWalletId = transaction.getFromWalletId();
        UUID toWalletId = transaction.getToWalletId();
        boolean isParty = (fromWalletId != null && isOwnedWallet(fromWalletId))
                || (toWalletId != null && isOwnedWallet(toWalletId));
        if (!isParty) {
            throw new ResourceNotFoundException(
                    "Transaction " + transaction.getId() + " not found");
        }
    }
}
