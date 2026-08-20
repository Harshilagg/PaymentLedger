package com.paymentledger.wallet.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByOriginalTransactionIdAndStatusNot(UUID originalTransactionId, TransactionStatus status);
}
