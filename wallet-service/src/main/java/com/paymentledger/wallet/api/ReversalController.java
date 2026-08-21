package com.paymentledger.wallet.api;

import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.concurrency.OptimisticLockRetrier;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.idempotency.IdempotencyService;
import com.paymentledger.wallet.transaction.ReversalService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
public class ReversalController {

    private final TransactionRepository transactionRepository;
    private final WalletAccess walletAccess;
    private final ReversalService reversalService;
    private final IdempotencyService idempotencyService;
    private final OptimisticLockRetrier retrier;

    public ReversalController(TransactionRepository transactionRepository, WalletAccess walletAccess,
                               ReversalService reversalService, IdempotencyService idempotencyService,
                               OptimisticLockRetrier retrier) {
        this.transactionRepository = transactionRepository;
        this.walletAccess = walletAccess;
        this.reversalService = reversalService;
        this.idempotencyService = idempotencyService;
        this.retrier = retrier;
    }

    @PostMapping("/transactions/{transactionId}/reversals")
    public ResponseEntity<TransactionResponse> reverse(
            @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey) {
        Transaction original = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        walletAccess.requireParty(original.getFromWalletId(), original.getToWalletId());

        String canonicalRequest = transactionId.toString();

        TransactionResponse response = retrier.withRetry("reversal of " + transactionId, () ->
                idempotencyService.execute(idempotencyKey, canonicalRequest, TransactionResponse.class,
                        () -> reversalService.initiateReversal(transactionId, idempotencyKey)));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
