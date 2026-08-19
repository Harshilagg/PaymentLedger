package com.paymentledger.wallet.api.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyMapperTest {

    @Test
    void convertsMinorUnitsToDecimalForATwoDecimalCurrency() {
        assertThat(MoneyMapper.toDecimal(105_099, "USD")).isEqualByComparingTo("1050.99");
    }

    @Test
    void convertsMinorUnitsToDecimalForAZeroDecimalCurrency() {
        assertThat(MoneyMapper.toDecimal(500, "JPY")).isEqualByComparingTo("500");
    }

    @Test
    void convertsDecimalToMinorUnitsRoundTrip() {
        long minor = MoneyMapper.toMinor(new BigDecimal("42.50"), "USD");

        assertThat(minor).isEqualTo(4250);
        assertThat(MoneyMapper.toDecimal(minor, "USD")).isEqualByComparingTo("42.50");
    }

    @Test
    void rejectsSubMinorUnitAmounts() {
        assertThatThrownBy(() -> MoneyMapper.toMinor(new BigDecimal("1.005"), "USD"))
                .isInstanceOf(ArithmeticException.class);
    }
}
