package com.paymentledger.wallet.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByOriginalTransactionIdAndStatusNot(UUID originalTransactionId, TransactionStatus status);

    /**
     * Ordering comes from the Pageable rather than the method name - TransactionController's
     * @PageableDefault sets createdAt DESC - so a caller can sort by something else without
     * fighting a hardcoded OrderBy clause baked into the query.
     */
    Page<Transaction> findByFromWalletIdOrToWalletId(UUID fromWalletId, UUID toWalletId, Pageable pageable);
}
