package com.paymentledger.wallet.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    private Wallet wallet;

    @BeforeEach
    void setUp() {
        wallet = new Wallet(UUID.randomUUID(), Currency.getInstance("USD"));
        wallet.credit(10_000);
    }

    @Test
    void reserveReducesAvailableButNotSettledBalance() {
        wallet.reserve(4_000);

        assertThat(wallet.getBalanceMinor()).isEqualTo(10_000);
        assertThat(wallet.getReservedMinor()).isEqualTo(4_000);
        assertThat(wallet.availableMinor()).isEqualTo(6_000);
    }

    @Test
    void reserveBeyondAvailableBalanceFails() {
        wallet.reserve(7_000);

        assertThatThrownBy(() -> wallet.reserve(4_000))
                .isInstanceOf(InsufficientFundsException.class);

        // the failed reservation must not have partially applied
        assertThat(wallet.getReservedMinor()).isEqualTo(7_000);
    }

    @Test
    void settleReservedDebitMovesFundsFromReservedToSpent() {
        wallet.reserve(4_000);

        wallet.settleReservedDebit(4_000);

        assertThat(wallet.getBalanceMinor()).isEqualTo(6_000);
        assertThat(wallet.getReservedMinor()).isEqualTo(0);
        assertThat(wallet.availableMinor()).isEqualTo(6_000);
    }

    @Test
    void releaseHoldRestoresAvailableBalanceWithoutTouchingSettledBalance() {
        wallet.reserve(4_000);

        wallet.releaseHold(4_000);

        assertThat(wallet.getBalanceMinor()).isEqualTo(10_000);
        assertThat(wallet.getReservedMinor()).isEqualTo(0);
        assertThat(wallet.availableMinor()).isEqualTo(10_000);
    }

    @Test
    void settlingMoreThanReservedIsRejected() {
        wallet.reserve(2_000);

        assertThatThrownBy(() -> wallet.settleReservedDebit(3_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void releasingMoreThanReservedIsRejected() {
        wallet.reserve(2_000);

        assertThatThrownBy(() -> wallet.releaseHold(3_000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void zeroOrNegativeAmountsAreRejected() {
        assertThatThrownBy(() -> wallet.reserve(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> wallet.credit(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
