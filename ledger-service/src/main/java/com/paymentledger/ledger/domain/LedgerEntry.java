package com.paymentledger.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Insert-only - the actual source of truth for the whole system. Never updated, never deleted.
 * The unique constraint on (transactionId, walletId, direction) is enforced both here in the
 * JPA-visible shape and, as the real backstop, by the matching DB constraint from V1 - see
 * SPEC.md "Idempotent event consumption".
 */
@Entity
@Table(name = "ledger_entry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LedgerEntry(UUID transactionId, UUID walletId, UUID accountId, Direction direction,
                        long amountMinor, String currency) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive, got " + amountMinor);
        }
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.walletId = walletId;
        this.accountId = accountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = Instant.now();
    }
}
