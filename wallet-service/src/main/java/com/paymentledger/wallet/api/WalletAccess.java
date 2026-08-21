package com.paymentledger.wallet.api;

import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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

    public Wallet loadOwnedWallet(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Account account = accountRepository.findById(wallet.getAccountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        AccountController.requireOwner(account);
        return wallet;
    }

    /** Non-throwing check, for endpoints (like reversals) where the caller may own either of two wallets. */
    public boolean isOwnedWallet(UUID walletId) {
        return walletRepository.findById(walletId)
                .map(Wallet::getAccountId)
                .flatMap(accountRepository::findById)
                .map(account -> account.getOwnerId().equals(CurrentUser.ownerId()))
                .orElse(false);
    }

    /** A transaction's two legs can belong to different owners (e.g. a transfer) - being a party
     * to either side is enough to read it. Used by ReversalController and TransactionController. */
    public void requireParty(UUID fromWalletId, UUID toWalletId) {
        boolean isParty = (fromWalletId != null && isOwnedWallet(fromWalletId))
                || (toWalletId != null && isOwnedWallet(toWalletId));
        if (!isParty) {
            throw new AccessDeniedException("Not a party to this transaction");
        }
    }
}
