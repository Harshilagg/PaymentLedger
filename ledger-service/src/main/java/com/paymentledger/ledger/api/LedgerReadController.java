package com.paymentledger.ledger.api;

import com.paymentledger.ledger.domain.LedgerEntryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The one read path into the ledger. Deliberately internal: the /internal prefix and the fact
 * that ledger-service's port is not published by docker-compose are what keep it off the public
 * surface, since this service has no security starter and performs no authorization of its own.
 * Callers must authorize before calling - wallet-service's TransactionController does exactly
 * that, checking that the caller is a party to the transaction first.
 *
 * Not paginated: a transaction has 2 ledger entries, or 4 when it crosses currencies. There is no
 * unbounded case to protect against.
 */
@RestController
@RequestMapping("/internal/ledger")
public class LedgerReadController {

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerReadController(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @GetMapping("/entries")
    public List<LedgerEntryResponse> entriesForTransaction(@RequestParam UUID transactionId) {
        return ledgerEntryRepository.findByTransactionIdOrderByCreatedAtAscIdAsc(transactionId).stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }
}
