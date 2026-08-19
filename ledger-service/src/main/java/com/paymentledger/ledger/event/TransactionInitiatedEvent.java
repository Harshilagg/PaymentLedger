package com.paymentledger.ledger.event;

import java.util.UUID;

/**
 * Consumed from the transaction-initiated topic, published by wallet-service's outbox relay.
 * fromWalletId/fromAccountId are null for DEPOSIT, toWalletId/toAccountId are null for
 * WITHDRAWAL; TRANSFER populates all four. Field names and shape must stay in lockstep with
 * wallet-service's producer-side event of the same name - there is no shared library, the JSON
 * contract is the interface.
 */
public record TransactionInitiatedEvent(
        UUID transactionId,
        TransactionType transactionType,
        UUID fromWalletId,
        UUID fromAccountId,
        UUID toWalletId,
        UUID toAccountId,
        long amountMinor,
        String currency) {
}
