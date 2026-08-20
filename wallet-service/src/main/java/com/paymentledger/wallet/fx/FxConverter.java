package com.paymentledger.wallet.fx;

import com.paymentledger.wallet.domain.ExchangeRate;
import com.paymentledger.wallet.domain.ExchangeRateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Converts a minor-unit amount from one currency to another using the seeded rate table. Same
 * currency is a no-op and never touches the table. Precision handling matters here because
 * currencies can have different fraction digits (USD has 2, JPY has 0) - the conversion is done
 * in major units via BigDecimal and only rounded to minor units at the very end, in the
 * destination currency's own scale.
 */
@Service
public class FxConverter {

    private final ExchangeRateRepository exchangeRateRepository;

    public FxConverter(ExchangeRateRepository exchangeRateRepository) {
        this.exchangeRateRepository = exchangeRateRepository;
    }

    public long convert(long fromAmountMinor, String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return fromAmountMinor;
        }

        ExchangeRate rate = exchangeRateRepository
                .findFirstByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc(fromCurrency, toCurrency)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No exchange rate available for " + fromCurrency + " -> " + toCurrency));

        int fromFractionDigits = Currency.getInstance(fromCurrency).getDefaultFractionDigits();
        int toFractionDigits = Currency.getInstance(toCurrency).getDefaultFractionDigits();

        BigDecimal fromMajor = BigDecimal.valueOf(fromAmountMinor, fromFractionDigits);
        BigDecimal toMajor = fromMajor.multiply(rate.getRate());
        return toMajor.setScale(toFractionDigits, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }
}
