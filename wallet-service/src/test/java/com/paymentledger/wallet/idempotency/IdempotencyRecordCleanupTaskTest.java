package com.paymentledger.wallet.idempotency;

import org.assertj.core.data.TemporalUnitWithinOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyRecordCleanupTaskTest {

    private final IdempotencyRecordRepository repository = mock(IdempotencyRecordRepository.class);
    private final IdempotencyRecordCleanupTask task = new IdempotencyRecordCleanupTask(repository);

    @Test
    void purgesRecordsExpiredAsOfNow() {
        when(repository.deleteByExpiresAtBefore(any())).thenReturn(3L);

        task.purgeExpired();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteByExpiresAtBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isCloseTo(Instant.now(), new TemporalUnitWithinOffset(5, ChronoUnit.SECONDS));
    }
}
