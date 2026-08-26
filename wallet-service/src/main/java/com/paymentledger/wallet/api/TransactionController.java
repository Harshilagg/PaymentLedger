package com.paymentledger.wallet.api;

import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Every mutating endpoint returns 202 with the transaction in PENDING - these are how a client
 * checks what happened after the saga settles, since there is no synchronous "did it work"
 * response. Being a party to a transaction (owning either its fromWallet or toWallet) is enough
 * to read it, same as reversing one.
 */
@RestController
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final WalletAccess walletAccess;

    public TransactionController(TransactionRepository transactionRepository, WalletAccess walletAccess) {
        this.transactionRepository = transactionRepository;
        this.walletAccess = walletAccess;
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse getTransaction(@PathVariable UUID id) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + id + " not found"));
        walletAccess.requireParty(transaction);
        return TransactionResponse.from(transaction);
    }

    @GetMapping("/wallets/{walletId}/transactions")
    public List<TransactionResponse> listTransactions(@PathVariable UUID walletId) {
        walletAccess.loadOwnedWallet(walletId);
        return transactionRepository.findByFromWalletIdOrToWalletIdOrderByCreatedAtDesc(walletId, walletId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
