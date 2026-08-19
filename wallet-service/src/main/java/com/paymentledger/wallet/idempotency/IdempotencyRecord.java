package com.paymentledger.wallet.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Written in the same local transaction as the operation it guards, so there is never a window
 * where the operation succeeds but the idempotency record fails to save - see SPEC.md
 * "Idempotency (client requests)". This is a distinct concern from ledger-service's Kafka
 * consumer dedup: this guards the client-facing HTTP API against retried requests.
 */
@Entity
@Table(name = "idempotency_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    @Id
    @Column(name = "key")
    private String key;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public IdempotencyRecord(String key, String requestHash, String responseBody, long ttlHours) {
        this.key = key;
        this.requestHash = requestHash;
        this.responseBody = responseBody;
        this.status = "COMPLETED";
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plus(ttlHours, ChronoUnit.HOURS);
    }

    public boolean matchesRequestHash(String otherHash) {
        return this.requestHash.equals(otherHash);
    }
}
