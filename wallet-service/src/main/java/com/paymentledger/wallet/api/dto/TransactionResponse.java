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
        UUID fromWalletId,
        UUID toWalletId,
        Instant createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getType().name(),
                transaction.getStatus().name(),
                MoneyMapper.toDecimal(transaction.getAmountMinor(), transaction.getCurrency()),
                transaction.getCurrency(),
                transaction.getFromWalletId(),
                transaction.getToWalletId(),
                transaction.getCreatedAt());
    }
}
