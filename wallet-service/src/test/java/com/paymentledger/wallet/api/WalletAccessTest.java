package com.paymentledger.wallet.api;

import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void requirePartyPassesWhenCallerOwnsTheFromWallet() {
        Wallet fromWallet = walletOwnedBy(callerId);
        UUID toWalletId = UUID.randomUUID();

        walletAccess.requireParty(fromWallet.getId(), toWalletId);
        // no exception - test passes
    }

    @Test
    void requirePartyPassesWhenCallerOwnsOnlyTheToWallet() {
        UUID fromWalletId = UUID.randomUUID();
        Wallet toWallet = walletOwnedBy(callerId);

        walletAccess.requireParty(fromWalletId, toWallet.getId());
    }

    @Test
    void requirePartyRejectsWhenCallerOwnsNeitherWallet() {
        Wallet fromWallet = walletOwnedBy(UUID.randomUUID());
        Wallet toWallet = walletOwnedBy(UUID.randomUUID());

        assertThatThrownBy(() -> walletAccess.requireParty(fromWallet.getId(), toWallet.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requirePartyRejectsWhenBothLegsAreNull() {
        assertThatThrownBy(() -> walletAccess.requireParty(null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void isOwnedWalletIsFalseForAMissingWallet() {
        UUID missingWalletId = UUID.randomUUID();
        when(walletRepository.findById(missingWalletId)).thenReturn(Optional.empty());

        assertThat(walletAccess.isOwnedWallet(missingWalletId)).isFalse();
    }
}
