package com.paymentledger.wallet.api;

import com.paymentledger.wallet.api.dto.AccountResponse;
import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    @GetMapping("/{id}")
    public AccountResponse getAccount(@PathVariable UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireOwner(account);
        return AccountResponse.from(account);
    }

    static void requireOwner(Account account) {
        if (!account.getOwnerId().equals(CurrentUser.ownerId())) {
            throw new AccessDeniedException("Not the owner of account " + account.getId());
        }
    }
}
