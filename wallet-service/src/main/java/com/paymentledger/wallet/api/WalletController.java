package com.paymentledger.wallet.api;

import com.paymentledger.wallet.api.dto.CreateWalletRequest;
import com.paymentledger.wallet.api.dto.WalletResponse;
import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

@RestController
public class WalletController {

    private final AccountRepository accountRepository;
    private final WalletRepository walletRepository;

    public WalletController(AccountRepository accountRepository, WalletRepository walletRepository) {
        this.accountRepository = accountRepository;
        this.walletRepository = walletRepository;
    }

    @PostMapping("/accounts/{accountId}/wallets")
    public ResponseEntity<WalletResponse> createWallet(@PathVariable UUID accountId,
                                                         @Valid @RequestBody CreateWalletRequest request) {
        Account account = requireOwnedAccount(accountId);

        if (walletRepository.findByAccountIdAndCurrency(accountId, request.currency()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Account already has a " + request.currency() + " wallet");
        }

        Wallet wallet = walletRepository.save(new Wallet(account.getId(), Currency.getInstance(request.currency())));
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    @GetMapping("/accounts/{accountId}/wallets")
    public List<WalletResponse> listWallets(@PathVariable UUID accountId) {
        requireOwnedAccount(accountId);
        return walletRepository.findByAccountId(accountId).stream()
                .map(WalletResponse::from)
                .toList();
    }

    // Missing wallet and someone else's wallet are answered identically - see SPEC.md
    // "Error handling" and the same treatment in WalletAccess#loadOwnedWallet.
    @GetMapping("/wallets/{id}")
    public WalletResponse getWallet(@PathVariable UUID id) {
        return walletRepository.findById(id)
                .filter(wallet -> accountRepository.findById(wallet.getAccountId())
                        .filter(AccountController::isOwner)
                        .isPresent())
                .map(WalletResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet " + id + " not found"));
    }

    private Account requireOwnedAccount(UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found"));
        AccountController.requireOwner(account);
        return account;
    }
}
