package com.paymentledger.wallet.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * IdempotencyRecord rows have a TTL (app.idempotency.ttl-hours) but nothing enforces it on its
 * own - without this, the table grows forever. Runs infrequently since staleness here has no
 * correctness consequence, only a storage one.
 */
@Component
public class IdempotencyRecordCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRecordCleanupTask.class);

    private final IdempotencyRecordRepository repository;

    public IdempotencyRecordCleanupTask(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${app.idempotency.cleanup-interval-ms}")
    @Transactional
    public void purgeExpired() {
        long deleted = repository.deleteByExpiresAtBefore(Instant.now());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency record(s)", deleted);
        }
    }
}
