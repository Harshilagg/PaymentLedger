package com.paymentledger.wallet.event;

import java.util.UUID;

/**
 * Published to the transaction-initiated topic for ledger-service to consume. fromWalletId/
 * fromAccountId are null for DEPOSIT, toWalletId/toAccountId are null for WITHDRAWAL.
 * fromAmountMinor/fromCurrency and toAmountMinor/toCurrency are equal for every same-currency
 * operation (deposit, withdrawal, same-currency transfer/reversal) and differ only for a
 * cross-currency transfer, where ledger-service needs both legs to post the FX-clearing-account
 * pair of entries - see SPEC.md's cross-currency data model. Field names and shape must stay in
 * lockstep with ledger-service's consumer-side event of the same name - there is no shared
 * library, the JSON contract is the interface.
 */
public record TransactionInitiatedEvent(
        UUID transactionId,
        TransactionType transactionType,
        UUID fromWalletId,
        UUID fromAccountId,
        UUID toWalletId,
        UUID toAccountId,
        long fromAmountMinor,
        String fromCurrency,
        long toAmountMinor,
        String toCurrency) {
}
