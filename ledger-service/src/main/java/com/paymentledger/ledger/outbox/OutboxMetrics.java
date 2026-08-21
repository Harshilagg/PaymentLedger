package com.paymentledger.ledger.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Outbox lag - how many rows are waiting to be relayed to Kafka - is the standard signal to
 * alert on for the transactional outbox pattern: a growing number here means either the relay
 * has stopped running or Kafka is unreachable, and either way messages are piling up undelivered.
 */
@Component
public class OutboxMetrics {

    public OutboxMetrics(MeterRegistry registry, OutboxEventRepository outboxEventRepository) {
        Gauge.builder("outbox.unpublished", outboxEventRepository, OutboxEventRepository::countByPublishedFalse)
                .description("Number of outbox_event rows not yet published to Kafka")
                .register(registry);
    }
}
