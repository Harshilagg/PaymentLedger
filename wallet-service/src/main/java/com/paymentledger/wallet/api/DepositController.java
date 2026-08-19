package com.paymentledger.wallet.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.dto.InitiateDepositRequest;
import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.idempotency.IdempotencyService;
import com.paymentledger.wallet.transaction.DepositService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class DepositController {

    private final WalletAccess walletAccess;
    private final DepositService depositService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public DepositController(WalletAccess walletAccess, DepositService depositService,
                              IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.walletAccess = walletAccess;
        this.depositService = depositService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/wallets/{walletId}/deposits")
    public ResponseEntity<TransactionResponse> deposit(
            @PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody InitiateDepositRequest request) {
        Wallet wallet = walletAccess.loadOwnedWallet(walletId);

        // The request DTO is already parsed by the time a controller method runs, so the raw
        // body bytes aren't available - canonical re-serialization (plus the path-param wallet
        // id, since it's not part of the body) is a stable enough stand-in for hashing purposes.
        String canonicalRequest = walletId + ":" + writeJson(request);

        TransactionResponse response = idempotencyService.execute(
                idempotencyKey, canonicalRequest, TransactionResponse.class,
                () -> depositService.initiateDeposit(wallet, request.amount(), idempotencyKey));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize request for idempotency hashing", e);
        }
    }
}
