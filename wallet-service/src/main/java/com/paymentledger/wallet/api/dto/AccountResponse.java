package com.paymentledger.wallet.api.dto;

import com.paymentledger.wallet.domain.Account;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, UUID ownerId, Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwnerId(), account.getCreatedAt());
    }
}
