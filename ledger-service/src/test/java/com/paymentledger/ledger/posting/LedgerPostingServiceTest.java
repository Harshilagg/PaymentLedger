package com.paymentledger.ledger.posting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.ledger.domain.Direction;
import com.paymentledger.ledger.domain.LedgerEntry;
import com.paymentledger.ledger.domain.LedgerEntryRepository;
import com.paymentledger.ledger.event.TransactionInitiatedEvent;
import com.paymentledger.ledger.event.TransactionType;
import com.paymentledger.ledger.outbox.OutboxEvent;
import com.paymentledger.ledger.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LedgerPostingServiceTest {

    private final LedgerEntryRepository ledgerEntryRepository = mock(LedgerEntryRepository.class);
    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final LedgerPostingService service = new LedgerPostingService(
            ledgerEntryRepository, outboxEventRepository, new ObjectMapper());

    private final UUID transactionId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void notAlreadyPosted() {
        when(ledgerEntryRepository.existsByTransactionId(transactionId)).thenReturn(false);
    }

    @Test
    void depositCreditsTheWalletAndDebitsTheExternalClearingAccount() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transactionId, TransactionType.DEPOSIT, null, null, walletId, accountId, 5_000, "USD");

        service.post(event);

        List<LedgerEntry> entries = captureSavedEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getWalletId()).isEqualTo(walletId);
            assertThat(e.getDirection()).isEqualTo(Direction.CREDIT);
            assertThat(e.getAmountMinor()).isEqualTo(5_000);
        });
        assertThat(entries).anySatisfy(e -> assertThat(e.getDirection()).isEqualTo(Direction.DEBIT));
        assertOutcomePublished("TRANSACTION_POSTED");
    }

    @Test
    void withdrawalDebitsTheWalletAndCreditsTheExternalClearingAccount() {
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transactionId, TransactionType.WITHDRAWAL, walletId, accountId, null, null, 3_000, "USD");

        service.post(event);

        List<LedgerEntry> entries = captureSavedEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getWalletId()).isEqualTo(walletId);
            assertThat(e.getDirection()).isEqualTo(Direction.DEBIT);
        });
        assertThat(entries).anySatisfy(e -> assertThat(e.getDirection()).isEqualTo(Direction.CREDIT));
        assertOutcomePublished("TRANSACTION_POSTED");
    }

    @Test
    void transferDebitsSourceAndCreditsDestinationWithNoClearingAccount() {
        UUID toWalletId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transactionId, TransactionType.TRANSFER, walletId, accountId, toWalletId, toAccountId,
                1_500, "USD");

        service.post(event);

        List<LedgerEntry> entries = captureSavedEntries();
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::getWalletId)
                .containsExactlyInAnyOrder(walletId, toWalletId);
        assertOutcomePublished("TRANSACTION_POSTED");
    }

    @Test
    void alreadyPostedTransactionIsANoOp() {
        when(ledgerEntryRepository.existsByTransactionId(transactionId)).thenReturn(true);
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transactionId, TransactionType.DEPOSIT, null, null, walletId, accountId, 5_000, "USD");

        service.post(event);

        verify(ledgerEntryRepository, never()).saveAll(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void malformedEventPublishesAFailedOutcomeInsteadOfThrowing() {
        // TRANSFER with no fromWallet - the producer side should never send this, but the
        // consumer must not crash and lose the message if it somehow does.
        TransactionInitiatedEvent event = new TransactionInitiatedEvent(
                transactionId, TransactionType.TRANSFER, null, null, walletId, accountId, 1_000, "USD");

        service.post(event);

        verify(ledgerEntryRepository, never()).saveAll(any());
        assertOutcomePublished("TRANSACTION_FAILED");
    }

    @SuppressWarnings("unchecked")
    private List<LedgerEntry> captureSavedEntries() {
        ArgumentCaptor<List<LedgerEntry>> captor = ArgumentCaptor.forClass(List.class);
        verify(ledgerEntryRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    private void assertOutcomePublished(String expectedEventType) {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(expectedEventType);
        assertThat(captor.getValue().getAggregateId()).isEqualTo(transactionId);
    }
}
