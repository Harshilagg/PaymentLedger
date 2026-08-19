package com.paymentledger.wallet.event;

/**
 * The wire-contract transaction types - deliberately narrower than domain.TransactionType, which
 * also has REVERSAL. A reversal is posted to the ledger as its own DEPOSIT/WITHDRAWAL/TRANSFER
 * shape (see SPEC.md's Reversal flow), not a fifth wire type.
 */
public enum TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    TRANSFER
}
