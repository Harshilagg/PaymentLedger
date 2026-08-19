package com.paymentledger.wallet.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findByAccountId(UUID accountId);

    Optional<Wallet> findByAccountIdAndCurrency(UUID accountId, String currency);
}
