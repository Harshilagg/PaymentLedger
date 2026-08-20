package com.paymentledger.wallet.domain;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRateId implements Serializable {
    private String fromCurrency;
    private String toCurrency;
    private Instant effectiveAt;
}
