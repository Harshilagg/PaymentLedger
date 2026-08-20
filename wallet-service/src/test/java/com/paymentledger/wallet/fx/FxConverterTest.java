package com.paymentledger.wallet.fx;

import com.paymentledger.wallet.domain.ExchangeRate;
import com.paymentledger.wallet.domain.ExchangeRateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FxConverterTest {

    private final ExchangeRateRepository exchangeRateRepository = mock(ExchangeRateRepository.class);
    private final FxConverter converter = new FxConverter(exchangeRateRepository);

    @Test
    void sameCurrencyIsAPassThroughAndNeverQueriesTheRateTable() {
        assertThat(converter.convert(10_000, "USD", "USD")).isEqualTo(10_000);
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
    }
}
