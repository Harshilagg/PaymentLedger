package com.paymentledger.wallet.fx;

import com.paymentledger.wallet.domain.ExchangeRate;
import com.paymentledger.wallet.domain.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FxConverterTest {

    private final ExchangeRateRepository exchangeRateRepository = mock(ExchangeRateRepository.class);
    private final ExchangeRateCache cache = mock(ExchangeRateCache.class);
    private final FxConverter converter = new FxConverter(exchangeRateRepository, cache);

    @BeforeEach
    void cacheStartsEmpty() {
        // Default to a miss so each test states its own caching expectation explicitly.
        when(cache.get(anyString(), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void sameCurrencyIsAPassThroughAndNeverQueriesTheRateTable() {
        assertThat(converter.convert(10_000, "USD", "USD")).isEqualTo(10_000);

        // Not even the cache: the short-circuit is why no same-currency workload can show any
        // effect from caching rates.
        verifyNoInteractions(cache);
        verifyNoInteractions(exchangeRateRepository);
    }

    @Test
    void aCacheHitIsUsedAndTheRepositoryIsNeverQueried() {
        when(cache.get("USD", "EUR")).thenReturn(Optional.of(new BigDecimal("0.92000000")));

        assertThat(converter.convert(10_000, "USD", "EUR")).isEqualTo(9_200);

        verifyNoInteractions(exchangeRateRepository);
    }

    @Test
    void aCacheMissLoadsFromTheRepositoryAndPopulatesTheCache() {
        ExchangeRate rate = new ExchangeRate("USD", "GBP", Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("0.79000000"));
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc("USD", "GBP"))
                .thenReturn(Optional.of(rate));

        assertThat(converter.convert(10_000, "USD", "GBP")).isEqualTo(7_900);

        verify(cache).put("USD", "GBP", new BigDecimal("0.79000000"));
    }

    @Test
    void convertsUsingTheSeededRate() {
        ExchangeRate rate = new ExchangeRate("USD", "EUR", Instant.parse("2026-01-01T00:00:00Z"),
                new BigDecimal("0.92000000"));
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc("USD", "EUR"))
                .thenReturn(Optional.of(rate));

        // $100.00 -> EUR at 0.92
        long result = converter.convert(10_000, "USD", "EUR");

        assertThat(result).isEqualTo(9_200);
    }

    @Test
    void missingRateIsRejected() {
        when(exchangeRateRepository.findFirstByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc("USD", "JPY"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convert(10_000, "USD", "JPY"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");

        // Absence is deliberately not cached: a rate added later must be visible immediately
        // rather than after a TTL nobody remembers setting.
        verify(cache, never()).put(anyString(), anyString(), any());
    }
}
