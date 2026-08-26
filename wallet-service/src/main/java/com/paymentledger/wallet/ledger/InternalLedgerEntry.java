package com.paymentledger.wallet.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.UUID;

/**
 * Mirrors ledger-service's wire shape, minor units and all. Kept separate from the public
 * LedgerEntryResponse so the conversion to BigDecimal happens once, at this service's own API
 * boundary, rather than leaking ledger-service's representation outwards.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalLedgerEntry(
        UUID id,
        UUID transactionId,
        UUID walletId,
        UUID accountId,
        String direction,
        long amountMinor,
        String currency,
        Instant createdAt) {
}
