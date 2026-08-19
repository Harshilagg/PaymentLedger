package com.paymentledger.ledger.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalClearingAccountTest {

    @Test
    void isDeterministicForTheSameCurrency() {
        assertThat(ExternalClearingAccount.walletIdFor("USD"))
                .isEqualTo(ExternalClearingAccount.walletIdFor("USD"));
    }

    @Test
    void isDistinctAcrossCurrencies() {
        assertThat(ExternalClearingAccount.walletIdFor("USD"))
                .isNotEqualTo(ExternalClearingAccount.walletIdFor("EUR"));
    }

    @Test
    void walletAndAccountIdsAreDistinct() {
        assertThat(ExternalClearingAccount.walletIdFor("USD"))
                .isNotEqualTo(ExternalClearingAccount.accountIdFor("USD"));
    }
}
