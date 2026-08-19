package com.paymentledger.wallet.event;

import java.util.UUID;

/** Consumed from the transaction-outcome topic, published by ledger-service's outbox relay. */
public record TransactionOutcomeEvent(UUID transactionId, OutcomeStatus status, String reason) {
}
