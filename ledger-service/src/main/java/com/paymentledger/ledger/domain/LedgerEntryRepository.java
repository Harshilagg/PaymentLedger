package com.paymentledger.ledger.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    /**
     * Entries have no sequence column, so created_at is the ordering. The four entries of one
     * posting are written inside a single transaction and can share a timestamp, hence the id
     * tiebreak - it carries no meaning, it just makes the order stable across calls.
     */
    List<LedgerEntry> findByTransactionIdOrderByCreatedAtAscIdAsc(UUID transactionId);

    boolean existsByTransactionId(UUID transactionId);
}
