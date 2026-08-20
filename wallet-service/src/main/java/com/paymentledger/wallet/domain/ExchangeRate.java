package com.paymentledger.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/** Seeded static table for v1 - see SPEC.md non-goals (no live rate feed). */
@Entity
@Table(name = "exchange_rate")
@IdClass(ExchangeRateId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExchangeRate {

    @Id
    @Column(name = "from_currency")
    private String fromCurrency;

    @Id
    @Column(name = "to_currency")
    private String toCurrency;

    @Id
    @Column(name = "effective_at")
    private Instant effectiveAt;

    @Column(name = "rate", nullable = false)
    private BigDecimal rate;

    public ExchangeRate(String fromCurrency, String toCurrency, Instant effectiveAt, BigDecimal rate) {
        this.fromCurrency = fromCurrency;
        this.toCurrency = toCurrency;
        this.effectiveAt = effectiveAt;
        this.rate = rate;
    }
}
