package com.paymentledger.wallet.transaction;

import com.paymentledger.wallet.event.TransactionOutcomeEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionOutcomeListener {

    private final SettlementService settlementService;

    public TransactionOutcomeListener(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @KafkaListener(topics = "${app.kafka.topics.transaction-outcome}")
    public void onMessage(TransactionOutcomeEvent event) {
        settlementService.applyOutcome(event);
    }
}
