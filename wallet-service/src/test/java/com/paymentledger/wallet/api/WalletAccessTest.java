package com.paymentledger.wallet.api;

import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalletAccessTest {

    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final WalletAccess walletAccess = new WalletAccess(walletRepository, accountRepository);

    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void authenticateAsCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(callerId, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private Wallet walletOwnedBy(UUID ownerId) {
        Account account = new Account(ownerId);
        Wallet wallet = new Wallet(account.getId(), Currency.getInstance("USD"));
        when(walletRepository.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return wallet;
    }

    private static Transaction transferBetween(UUID fromWalletId, UUID toWalletId) {
        return Transaction.initiateTransfer(fromWalletId, toWalletId, 1_000, "USD", "key");
    }

    @Test
    void requirePartyPassesWhenCallerOwnsTheFromWallet() {
        Wallet fromWallet = walletOwnedBy(callerId);
        UUID toWalletId = UUID.randomUUID();

        walletAccess.requireParty(transferBetween(fromWallet.getId(), toWalletId));
        // no exception - test passes
    }

    @Test
    void requirePartyPassesWhenCallerOwnsOnlyTheToWallet() {
        UUID fromWalletId = UUID.randomUUID();
        Wallet toWallet = walletOwnedBy(callerId);

        walletAccess.requireParty(transferBetween(fromWalletId, toWallet.getId()));
    }

    // 404 rather than 403: a caller who is not a party must not be able to tell an existing
    // transaction from an imaginary one. See SPEC.md "Error handling".
    @Test
    void requirePartyRejectsWithNotFoundWhenCallerOwnsNeitherWallet() {
        Wallet fromWallet = walletOwnedBy(UUID.randomUUID());
        Wallet toWallet = walletOwnedBy(UUID.randomUUID());
        Transaction transaction = transferBetween(fromWallet.getId(), toWallet.getId());

        assertThatThrownBy(() -> walletAccess.requireParty(transaction))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Transaction " + transaction.getId() + " not found");
    }

    @Test
    void requirePartyRejectsWhenBothLegsAreNull() {
        assertThatThrownBy(() -> walletAccess.requireParty(transferBetween(null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void loadOwnedWalletRejectsAMissingWalletAndSomeoneElsesIdentically() {
        UUID missingWalletId = UUID.randomUUID();
        when(walletRepository.findById(missingWalletId)).thenReturn(Optional.empty());
        Wallet someoneElses = walletOwnedBy(UUID.randomUUID());

        Throwable missing = catchThrowable(() -> walletAccess.loadOwnedWallet(missingWalletId));
        Throwable notMine = catchThrowable(() -> walletAccess.loadOwnedWallet(someoneElses.getId()));

        assertThat(missing).isInstanceOf(ResourceNotFoundException.class);
        assertThat(notMine).isInstanceOf(ResourceNotFoundException.class);
        // Same class, and the only difference in the message is the id the caller already supplied.
        assertThat(missing.getMessage()).isEqualTo("Wallet " + missingWalletId + " not found");
        assertThat(notMine.getMessage()).isEqualTo("Wallet " + someoneElses.getId() + " not found");
    }

    @Test
    void loadOwnedWalletReturnsTheWalletWhenTheCallerOwnsIt() {
        Wallet mine = walletOwnedBy(callerId);

        assertThat(walletAccess.loadOwnedWallet(mine.getId())).isSameAs(mine);
    }

    @Test
    void isOwnedWalletIsFalseForAMissingWallet() {
        UUID missingWalletId = UUID.randomUUID();
        when(walletRepository.findById(missingWalletId)).thenReturn(Optional.empty());

        assertThat(walletAccess.isOwnedWallet(missingWalletId)).isFalse();
    }
}
