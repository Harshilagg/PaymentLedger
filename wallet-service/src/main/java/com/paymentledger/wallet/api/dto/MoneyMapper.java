package com.paymentledger.wallet.api.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * The only place minor-unit longs and API-facing BigDecimals meet, per SPEC.md's money
 * representation rule: long minor units everywhere internally, BigDecimal only at the boundary.
 */
public final class MoneyMapper {

    private MoneyMapper() {
    }

    public static BigDecimal toDecimal(long amountMinor, String currencyCode) {
        int fractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        return BigDecimal.valueOf(amountMinor, fractionDigits);
    }

    public static long toMinor(BigDecimal amount, String currencyCode) {
        int fractionDigits = Currency.getInstance(currencyCode).getDefaultFractionDigits();
        return amount.setScale(fractionDigits, RoundingMode.UNNECESSARY)
                .unscaledValue()
                .longValueExact();
    }
}
