package com.paymentledger.ledger.event;

import java.util.UUID;

/**
 * Published to the transaction-outcome topic for wallet-service to consume. reason is null when
 * status is POSTED.
 */
public record TransactionOutcomeEvent(UUID transactionId, OutcomeStatus status, String reason) {
}
