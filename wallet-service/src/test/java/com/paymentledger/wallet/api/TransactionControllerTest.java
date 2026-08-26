package com.paymentledger.wallet.api;

import com.paymentledger.wallet.api.dto.LedgerEntryResponse;
import com.paymentledger.wallet.domain.Account;
import com.paymentledger.wallet.domain.AccountRepository;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.ledger.InternalLedgerEntry;
import com.paymentledger.wallet.ledger.LedgerServiceClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TransactionControllerTest {

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final LedgerServiceClient ledgerServiceClient = mock(LedgerServiceClient.class);
    private final WalletAccess walletAccess = new WalletAccess(walletRepository, accountRepository);
    private final TransactionController controller =
            new TransactionController(transactionRepository, walletAccess, ledgerServiceClient);

    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void authenticateAsCaller() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(callerId, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private Wallet walletOwnedBy(UUID ownerId) {
        Account account = new Account(ownerId);
        Wallet wallet = new Wallet(account.getId(), Currency.getInstance("USD"));
        when(walletRepository.findById(wallet.getId())).thenReturn(Optional.of(wallet));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));
        return wallet;
    }

    private Transaction depositInto(Wallet wallet) {
        Transaction transaction = Transaction.initiateDeposit(wallet.getId(), 2_500, "USD", "key");
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        return transaction;
    }

    @Test
    void ledgerEntriesAreConvertedToDecimalAtTheApiBoundary() {
        Transaction transaction = depositInto(walletOwnedBy(callerId));
        UUID walletId = UUID.randomUUID();
        when(ledgerServiceClient.fetchEntries(transaction.getId())).thenReturn(List.of(
                new InternalLedgerEntry(UUID.randomUUID(), transaction.getId(), walletId,
                        UUID.randomUUID(), "CREDIT", 2_500, "USD", Instant.now())));

        List<LedgerEntryResponse> entries = controller.getLedgerEntries(transaction.getId());

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.direction()).isEqualTo("CREDIT");
            assertThat(entry.amount()).isEqualByComparingTo("25.00");
            assertThat(entry.currency()).isEqualTo("USD");
            assertThat(entry.walletId()).isEqualTo(walletId);
        });
    }

    /**
     * ledger-service has no security of its own, so "authorize first" is not a stylistic
     * preference - reaching the outbound call at all would already have leaked the entries.
     */
    @Test
    void ledgerEntriesAreNeverFetchedForSomeoneWhoIsNotAPartyToTheTransaction() {
        Transaction transaction = depositInto(walletOwnedBy(UUID.randomUUID()));

        assertThatThrownBy(() -> controller.getLedgerEntries(transaction.getId()))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(ledgerServiceClient);
    }

    @Test
    void ledgerEntriesForAMissingTransactionNeverReachLedgerService() {
        UUID unknownId = UUID.randomUUID();
        when(transactionRepository.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.getLedgerEntries(unknownId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Transaction " + unknownId + " not found");

        verifyNoInteractions(ledgerServiceClient);
    }

    @Test
    void listTransactionsPassesThePageableStraightThroughToTheRepository() {
        Wallet wallet = walletOwnedBy(callerId);
        Pageable pageable = PageRequest.of(2, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Transaction transaction = Transaction.initiateDeposit(wallet.getId(), 1_000, "USD", "key");
        when(transactionRepository.findByFromWalletIdOrToWalletId(wallet.getId(), wallet.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(transaction), pageable, 41));

        Page<?> page = controller.listTransactions(wallet.getId(), pageable);

        verify(transactionRepository).findByFromWalletIdOrToWalletId(wallet.getId(), wallet.getId(), pageable);
        assertThat(page.getTotalElements()).isEqualTo(41);
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void listTransactionsRejectsAWalletTheCallerDoesNotOwnBeforeQueryingAnything() {
        Wallet someoneElses = walletOwnedBy(UUID.randomUUID());

        assertThatThrownBy(() -> controller.listTransactions(someoneElses.getId(), Pageable.unpaged()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(transactionRepository, org.mockito.Mockito.never())
                .findByFromWalletIdOrToWalletId(any(), any(), any());
    }
}
