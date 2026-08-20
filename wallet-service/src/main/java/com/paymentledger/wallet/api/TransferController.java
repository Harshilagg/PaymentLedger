package com.paymentledger.wallet.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.dto.InitiateTransferRequest;
import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.concurrency.OptimisticLockRetrier;
import com.paymentledger.wallet.idempotency.IdempotencyService;
import com.paymentledger.wallet.transaction.TransferService;
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
public class TransferController {

    private final WalletAccess walletAccess;
    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final OptimisticLockRetrier retrier;
    private final ObjectMapper objectMapper;

    public TransferController(WalletAccess walletAccess, TransferService transferService,
                               IdempotencyService idempotencyService, OptimisticLockRetrier retrier,
                               ObjectMapper objectMapper) {
        this.walletAccess = walletAccess;
        this.transferService = transferService;
        this.idempotencyService = idempotencyService;
        this.retrier = retrier;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/wallets/{walletId}/transfers")
    public ResponseEntity<TransactionResponse> transfer(
            @PathVariable UUID walletId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody InitiateTransferRequest request) {
        walletAccess.loadOwnedWallet(walletId); // ownership check only - not reused for the mutation, see TransferService

        String canonicalRequest = walletId + ":" + writeJson(request);

        TransactionResponse response = retrier.withRetry("transfer from wallet " + walletId, () ->
                idempotencyService.execute(
                        idempotencyKey, canonicalRequest, TransactionResponse.class,
                        () -> transferService.initiateTransfer(
                                walletId, request.toWalletId(), request.amount(), idempotencyKey)));

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
