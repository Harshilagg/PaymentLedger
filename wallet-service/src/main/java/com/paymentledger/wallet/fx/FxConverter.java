package com.paymentledger.wallet.fx;

import com.paymentledger.wallet.domain.ExchangeRate;
import com.paymentledger.wallet.domain.ExchangeRateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Optional;

/**
 * Converts a minor-unit amount from one currency to another using the seeded rate table, consulting
 * ExchangeRateCache first. Same currency is a no-op and touches neither the cache nor the table -
 * which is why only cross-currency traffic can show any effect from that cache.
 *
 * Precision handling matters here because currencies can have different fraction digits (USD has 2,
 * JPY has 0) - the conversion is done in major units via BigDecimal and only rounded to minor units
 * at the very end, in the destination currency's own scale.
 */
@Service
public class FxConverter {

    private final ExchangeRateRepository exchangeRateRepository;
    private final ExchangeRateCache cache;

    public FxConverter(ExchangeRateRepository exchangeRateRepository, ExchangeRateCache cache) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.cache = cache;
    }

    public long convert(long fromAmountMinor, String fromCurrency, String toCurrency) {
        if (fromCurrency.equals(toCurrency)) {
            return fromAmountMinor;
        }

        BigDecimal rate = rateFor(fromCurrency, toCurrency);

        int fromFractionDigits = Currency.getInstance(fromCurrency).getDefaultFractionDigits();
        int toFractionDigits = Currency.getInstance(toCurrency).getDefaultFractionDigits();

        BigDecimal fromMajor = BigDecimal.valueOf(fromAmountMinor, fromFractionDigits);
        BigDecimal toMajor = fromMajor.multiply(rate);
        return toMajor.setScale(toFractionDigits, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    /**
     * Cache first, database second. A miss - including a Redis failure, which the cache reports as a
     * miss - simply costs the lookup that would have happened anyway.
     *
     * A missing pair is deliberately NOT cached as a negative result. It is a rare path that ends in
     * a 422, and caching absence would mean a rate added later is invisible until the entry expires.
     */
    private BigDecimal rateFor(String fromCurrency, String toCurrency) {
        Optional<BigDecimal> cached = cache.get(fromCurrency, toCurrency);
        if (cached.isPresent()) {
            return cached.get();
        }

        ExchangeRate rate = exchangeRateRepository
                .findFirstByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc(fromCurrency, toCurrency)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No exchange rate available for " + fromCurrency + " -> " + toCurrency));

        cache.put(fromCurrency, toCurrency, rate.getRate());
        return rate.getRate();
    }
}
