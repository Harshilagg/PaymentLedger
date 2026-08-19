package com.paymentledger.ledger.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls outbox_event for unpublished rows and republishes them - never touches business tables,
 * only this table. A row is marked published only after Kafka has acknowledged the send, so a
 * broker hiccup just leaves the row for the next poll instead of silently dropping the event.
 * See SPEC.md "Cross-service correctness: saga + transactional outbox".
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final int batchSize;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                        KafkaTemplate<String, String> kafkaTemplate,
                        @Value("${app.kafka.topics.transaction-outcome}") String topic,
                        @Value("${app.outbox.relay.batch-size}") int batchSize) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.poll-interval-ms}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(Limit.of(batchSize));
        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(topic, event.getAggregateId().toString(), event.getPayloadJson())
                        .get(5, TimeUnit.SECONDS);
                event.markPublished();
            } catch (Exception e) {
                log.warn("Failed to publish outbox event {} ({}), will retry on next poll",
                        event.getId(), event.getEventType(), e);
            }
        }
    }
}
