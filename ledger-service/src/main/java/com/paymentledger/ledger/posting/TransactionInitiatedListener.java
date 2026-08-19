package com.paymentledger.ledger.posting;

import com.paymentledger.ledger.event.TransactionInitiatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionInitiatedListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionInitiatedListener.class);

    private final LedgerPostingService postingService;

    public TransactionInitiatedListener(LedgerPostingService postingService) {
        this.postingService = postingService;
    }

    @KafkaListener(topics = "${app.kafka.topics.transaction-initiated}")
    public void onMessage(TransactionInitiatedEvent event) {
        try {
            postingService.post(event);
        } catch (DataIntegrityViolationException raceLostToUniqueConstraint) {
            // The pre-check in postingService.post() missed a concurrent redelivery; the DB
            // constraint from V1 caught it instead. Same outcome either way - safe no-op.
            log.info("Duplicate delivery for transaction {} caught by the unique constraint",
                    event.transactionId());
        }
    }
}
