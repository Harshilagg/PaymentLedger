package com.paymentledger.wallet.api.dto;

import com.paymentledger.wallet.domain.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID id,
        UUID accountId,
        String currency,
        String status,
        BigDecimal balance,
        BigDecimal reserved,
        BigDecimal available,
        Instant createdAt) {

    public static WalletResponse from(Wallet wallet) {
        String currency = wallet.getCurrency();
        return new WalletResponse(
                wallet.getId(),
                wallet.getAccountId(),
                currency,
                wallet.getStatus().name(),
                MoneyMapper.toDecimal(wallet.getBalanceMinor(), currency),
                MoneyMapper.toDecimal(wallet.getReservedMinor(), currency),
                MoneyMapper.toDecimal(wallet.availableMinor(), currency),
                wallet.getCreatedAt());
    }
}
