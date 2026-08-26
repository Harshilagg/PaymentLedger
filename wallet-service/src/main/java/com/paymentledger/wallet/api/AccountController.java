package com.paymentledger.wallet.api;

import com.paymentledger.wallet.api.dto.AccountResponse;
import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount() {
        Account account = accountRepository.save(new Account(CurrentUser.ownerId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }

    @GetMapping
    public List<AccountResponse> listMyAccounts() {
        return accountRepository.findByOwnerId(CurrentUser.ownerId()).stream()
                .map(AccountResponse::from)
                .toList();
    }

    /**
     * An account that does not exist and an account belonging to somebody else are answered
     * identically, down to the message. Splitting them into 404 and 403 would turn this endpoint
     * into an oracle for "is this a real account id?" - see SPEC.md "Error handling".
     */
    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable UUID id) {
        return accountRepository.findById(id)
                .filter(AccountController::isOwner)
                .map(AccountResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + id + " not found"));
    }

    static boolean isOwner(Account account) {
        return account.getOwnerId().equals(CurrentUser.ownerId());
    }

    static void requireOwner(Account account) {
        if (!isOwner(account)) {
            throw new ResourceNotFoundException("Account " + account.getId() + " not found");
        }
    }
}
