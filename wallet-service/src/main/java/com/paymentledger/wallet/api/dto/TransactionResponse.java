package com.paymentledger.wallet.api.dto;

import com.paymentledger.wallet.domain.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        String type,
        String status,
        BigDecimal amount,
        String currency,
        BigDecimal toAmount,
        String toCurrency,
        UUID fromWalletId,
        UUID toWalletId,
        Instant createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        boolean crossCurrency = !transaction.toLegCurrency().equals(transaction.getCurrency());
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType().name(),
                transaction.getStatus().name(),
                MoneyMapper.toDecimal(transaction.getAmountMinor(), transaction.getCurrency()),
                transaction.getCurrency(),
                crossCurrency ? MoneyMapper.toDecimal(transaction.toLegAmountMinor(), transaction.toLegCurrency()) : null,
                crossCurrency ? transaction.toLegCurrency() : null,
                transaction.getFromWalletId(),
                transaction.getToWalletId(),
                transaction.getCreatedAt());
    }
}
