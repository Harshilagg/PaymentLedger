package com.paymentledger.ledger.api;

import com.paymentledger.ledger.domain.LedgerEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * Amounts stay as raw minor units here. This is a service-to-service wire, not a public API
 * boundary, and wallet-service already owns the BigDecimal conversion via its MoneyMapper -
 * duplicating that logic into a second service would mean two places that have to agree about
 * how many decimal places a currency has.
 */
public record LedgerEntryResponse(
        UUID id,
        UUID transactionId,
        UUID walletId,
        UUID accountId,
        String direction,
        long amountMinor,
        String currency,
        Instant createdAt) {

    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransactionId(),
                entry.getWalletId(),
                entry.getAccountId(),
                entry.getDirection().name(),
                entry.getAmountMinor(),
                entry.getCurrency(),
                entry.getCreatedAt());
    }
}
