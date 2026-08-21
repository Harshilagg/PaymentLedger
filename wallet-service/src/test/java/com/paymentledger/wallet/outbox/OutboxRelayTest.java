package com.paymentledger.wallet.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxRelay relay = new OutboxRelay(outboxEventRepository, kafkaTemplate, "the-topic", 50);

    @Test
    void publishesEachUnpublishedEventAndMarksItPublishedOnSuccess() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "TRANSACTION_INITIATED", "{}");
        when(outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("the-topic"), eq(event.getAggregateId().toString()), eq("{}")))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relay();

        assertThat(event.isPublished()).isTrue();
    }

    @Test
    void leavesTheEventUnpublishedWhenKafkaSendFails() {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), "TRANSACTION_INITIATED", "{}");
        when(outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send(eq("the-topic"), eq(event.getAggregateId().toString()), eq("{}")))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));

        relay.relay();

        assertThat(event.isPublished()).isFalse();
    }

    @Test
    void oneFailingEventDoesNotStopTheRestOfTheBatchFromPublishing() {
        OutboxEvent failing = new OutboxEvent(UUID.randomUUID(), "TRANSACTION_INITIATED", "{\"a\":1}");
        OutboxEvent succeeding = new OutboxEvent(UUID.randomUUID(), "TRANSACTION_INITIATED", "{\"a\":2}");
        when(outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc(any(Limit.class)))
                .thenReturn(List.of(failing, succeeding));
        when(kafkaTemplate.send(eq("the-topic"), eq(failing.getAggregateId().toString()), any()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker unavailable")));
        when(kafkaTemplate.send(eq("the-topic"), eq(succeeding.getAggregateId().toString()), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        relay.relay();

        assertThat(failing.isPublished()).isFalse();
        assertThat(succeeding.isPublished()).isTrue();
    }
}
