package com.paymentledger.wallet.transaction;

import com.paymentledger.wallet.concurrency.OptimisticLockRetrier;
import com.paymentledger.wallet.event.TransactionOutcomeEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionOutcomeListener {

    private final SettlementService settlementService;
    private final OptimisticLockRetrier retrier;

    public TransactionOutcomeListener(SettlementService settlementService, OptimisticLockRetrier retrier) {
        this.settlementService = settlementService;
        this.retrier = retrier;
    }

    @KafkaListener(topics = "${app.kafka.topics.transaction-outcome}")
    public void onMessage(TransactionOutcomeEvent event) {
        // Settlement can touch two wallets (transfer) and race against other settlements or
        // initiations on either one. If retries are exhausted here, deliberately let the
        // exception propagate rather than marking the transaction FAILED: ledger-service has
        // already posted the entries by this point (this listener only runs on outcomes), so
        // the ledger is the truth and the wallet balance must eventually catch up, not diverge
        // from it. Propagating leaves the message unacknowledged for redelivery.
        retrier.withRetry("settlement of transaction " + event.transactionId(),
                () -> {
                    settlementService.applyOutcome(event);
                    return null;
                });
    }
}
