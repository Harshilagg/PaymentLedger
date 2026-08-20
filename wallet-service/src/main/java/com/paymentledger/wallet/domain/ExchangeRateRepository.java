package com.paymentledger.wallet.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, ExchangeRateId> {

    Optional<ExchangeRate> findFirstByFromCurrencyAndToCurrencyOrderByEffectiveAtDesc(
            String fromCurrency, String toCurrency);
}
