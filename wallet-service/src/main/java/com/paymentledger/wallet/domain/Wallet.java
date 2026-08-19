package com.paymentledger.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * The materialized balance read-model and the sole optimistic-locking contention point in
 * wallet-service. balanceMinor is settled funds; reservedMinor is funds held against pending
 * debits (withdrawal, outgoing transfer). Available balance is always balanceMinor - reservedMinor,
 * computed rather than stored, so it can never drift out of sync with the two source columns.
 */
@Entity
@Table(name = "wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "reserved_minor", nullable = false)
    private long reservedMinor;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Wallet(UUID accountId, Currency currency) {
        this.id = UUID.randomUUID();
        this.accountId = accountId;
        this.currency = currency.getCurrencyCode();
        this.status = WalletStatus.ACTIVE.name();
        this.balanceMinor = 0L;
        this.reservedMinor = 0L;
        this.createdAt = Instant.now();
    }

    public WalletStatus getStatus() {
        return WalletStatus.valueOf(status);
    }

    public long availableMinor() {
        return balanceMinor - reservedMinor;
    }

    /**
     * Holds funds against a pending debit (withdrawal or outgoing transfer leg). Must run in the
     * same local transaction as the saga-initiating write so the check and the hold are atomic -
     * see SPEC.md "Balance reservation".
     */
    public void reserve(long amountMinor) {
        requirePositive(amountMinor);
        requireActive();
        if (availableMinor() < amountMinor) {
            throw new InsufficientFundsException(id, amountMinor, availableMinor());
        }
        this.reservedMinor += amountMinor;
        assertInvariants();
    }

    /** Converts a hold into a settled debit once ledger-service confirms the posting. */
    public void settleReservedDebit(long amountMinor) {
        requirePositive(amountMinor);
        if (reservedMinor < amountMinor) {
            throw new IllegalStateException(
                    "Cannot settle " + amountMinor + " minor units against wallet " + id
                            + ": only " + reservedMinor + " reserved");
        }
        this.reservedMinor -= amountMinor;
        this.balanceMinor -= amountMinor;
        assertInvariants();
    }

    /** Releases a hold without touching settled balance - used on saga failure/compensation. */
    public void releaseHold(long amountMinor) {
        requirePositive(amountMinor);
        if (reservedMinor < amountMinor) {
            throw new IllegalStateException(
                    "Cannot release " + amountMinor + " minor units on wallet " + id
                            + ": only " + reservedMinor + " reserved");
        }
        this.reservedMinor -= amountMinor;
        assertInvariants();
    }

    /** Credits never require a hold - crediting can't overdraft. */
    public void credit(long amountMinor) {
        requirePositive(amountMinor);
        requireActive();
        this.balanceMinor += amountMinor;
        assertInvariants();
    }

    private void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be positive, got " + amountMinor);
        }
    }

    private void requireActive() {
        if (getStatus() != WalletStatus.ACTIVE) {
            throw new IllegalStateException("Wallet " + id + " is not active (status=" + status + ")");
        }
    }

    /**
     * Defense in depth for the concurrency test suite: fails loudly the instant an invariant
     * would be violated, rather than relying on an external observer polling the row (which is
     * racy) - see SPEC.md "Testing strategy".
     */
    private void assertInvariants() {
        if (balanceMinor < 0) {
            throw new IllegalStateException("Invariant violated: balanceMinor < 0 for wallet " + id);
        }
        if (reservedMinor < 0) {
            throw new IllegalStateException("Invariant violated: reservedMinor < 0 for wallet " + id);
        }
        if (reservedMinor > balanceMinor) {
            throw new IllegalStateException(
                    "Invariant violated: reservedMinor > balanceMinor for wallet " + id);
        }
    }
}
