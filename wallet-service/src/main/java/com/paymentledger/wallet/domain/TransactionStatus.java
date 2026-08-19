package com.paymentledger.wallet.domain;

/**
 * PENDING -> COMPLETED on the happy path.
 * PENDING -> COMPENSATING -> FAILED when ledger-service rejects the posting for a reason other
 * than insufficient funds (that case is prevented earlier, at reservation time - see Wallet).
 */
public enum TransactionStatus {
    PENDING,
    COMPLETED,
    COMPENSATING,
    FAILED
}
