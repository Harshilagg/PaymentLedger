package com.paymentledger.wallet.api;

import com.paymentledger.wallet.api.dto.LedgerEntryResponse;
import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.ledger.LedgerServiceClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
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
    private final LedgerServiceClient ledgerServiceClient;

    public TransactionController(TransactionRepository transactionRepository,
                                  WalletAccess walletAccess,
                                  LedgerServiceClient ledgerServiceClient) {
        this.transactionRepository = transactionRepository;
        this.walletAccess = walletAccess;
        this.ledgerServiceClient = ledgerServiceClient;
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse getTransaction(@PathVariable UUID id) {
        return TransactionResponse.from(loadTransactionAsParty(id));
    }

    /**
     * The underlying double-entry rows, read straight from ledger-service. Authorization happens
     * first and entirely here: ledger-service does no access control of its own, so an
     * unauthorized caller must never reach the point of making the outbound call.
     */
    @GetMapping("/transactions/{id}/ledger-entries")
    public List<LedgerEntryResponse> getLedgerEntries(@PathVariable UUID id) {
        Transaction transaction = loadTransactionAsParty(id);
        return ledgerServiceClient.fetchEntries(transaction.getId()).stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }

    @GetMapping("/wallets/{walletId}/transactions")
    public Page<TransactionResponse> listTransactions(
            @PathVariable UUID walletId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        walletAccess.loadOwnedWallet(walletId);
        return transactionRepository
                .findByFromWalletIdOrToWalletId(walletId, walletId, pageable)
                .map(TransactionResponse::from);
    }

    private Transaction loadTransactionAsParty(UUID id) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + id + " not found"));
        walletAccess.requireParty(transaction);
        return transaction;
    }
}
