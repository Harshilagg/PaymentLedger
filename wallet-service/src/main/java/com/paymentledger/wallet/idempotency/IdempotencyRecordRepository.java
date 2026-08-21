package com.paymentledger.wallet.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    long deleteByExpiresAtBefore(Instant cutoff);
}
