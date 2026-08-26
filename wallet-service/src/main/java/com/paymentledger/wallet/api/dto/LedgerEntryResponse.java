package com.paymentledger.wallet.api.dto;

import com.paymentledger.wallet.ledger.InternalLedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The double-entry detail behind a transaction, as seen from outside. transactionId is omitted -
 * the URL already scopes the request to one transaction - and amounts become BigDecimal here,
 * which is the actual API boundary (see MoneyMapper and SPEC.md's money representation rule).
 */
public record LedgerEntryResponse(
        UUID id,
        UUID walletId,
        String direction,
        BigDecimal amount,
        String currency,
        Instant createdAt) {

    public static LedgerEntryResponse from(InternalLedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.id(),
                entry.walletId(),
                entry.direction(),
                MoneyMapper.toDecimal(entry.amountMinor(), entry.currency()),
                entry.currency(),
                entry.createdAt());
    }
}
