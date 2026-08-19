package com.paymentledger.wallet.domain;

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
 * The saga instance record. Every consumer that mutates wallet balances in response to a Kafka
 * event must check isPending() before applying its mutation and transition status in the same
 * local transaction as that mutation - this is what makes redelivery of transfer.posted /
 * transfer.failed a safe no-op instead of double-settling. See SPEC.md "Idempotent event
 * consumption".
 */
@Entity
@Table(name = "transaction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transaction {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private TransactionType type;

    @Column(name = "from_wallet_id")
    private UUID fromWalletId;

    @Column(name = "to_wallet_id")
    private UUID toWalletId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "original_transaction_id")
    private UUID originalTransactionId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    private Transaction(TransactionType type, UUID fromWalletId, UUID toWalletId,
                         long amountMinor, String currency, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.fromWalletId = fromWalletId;
        this.toWalletId = toWalletId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.status = TransactionStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public static Transaction initiateDeposit(UUID toWalletId, long amountMinor, String currency,
                                               String idempotencyKey) {
        return new Transaction(TransactionType.DEPOSIT, null, toWalletId, amountMinor, currency,
                idempotencyKey);
    }

    public static Transaction initiateWithdrawal(UUID fromWalletId, long amountMinor,
                                                  String currency, String idempotencyKey) {
        return new Transaction(TransactionType.WITHDRAWAL, fromWalletId, null, amountMinor,
                currency, idempotencyKey);
    }

    public static Transaction initiateTransfer(UUID fromWalletId, UUID toWalletId,
                                                long amountMinor, String currency,
                                                String idempotencyKey) {
        return new Transaction(TransactionType.TRANSFER, fromWalletId, toWalletId, amountMinor,
                currency, idempotencyKey);
    }

    public boolean isPending() {
        return status == TransactionStatus.PENDING;
    }

    /** Must only be called after the guarding isPending() check in the consumer - see class doc. */
    public void markCompleted() {
        requirePending();
        this.status = TransactionStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    public void markCompensating(String reason) {
        requirePending();
        this.status = TransactionStatus.COMPENSATING;
        this.failureReason = reason;
    }

    public void markFailed(String reason) {
        if (status != TransactionStatus.PENDING && status != TransactionStatus.COMPENSATING) {
            throw new IllegalStateException(
                    "Cannot mark transaction " + id + " FAILED from status " + status);
        }
        this.status = TransactionStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    private void requirePending() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "Transaction " + id + " is not PENDING (status=" + status
                            + ") - caller should have guarded with isPending() first");
        }
    }
}
