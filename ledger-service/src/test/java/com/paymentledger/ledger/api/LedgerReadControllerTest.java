package com.paymentledger.ledger.api;

import com.paymentledger.ledger.domain.Direction;
import com.paymentledger.ledger.domain.LedgerEntry;
import com.paymentledger.ledger.domain.LedgerEntryRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LedgerReadControllerTest {

    private final LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
    private final LedgerReadController controller = new LedgerReadController(repository);

    @Test
    void mapsEntriesWithoutConvertingAmountsOutOfMinorUnits() {
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        LedgerEntry debit = new LedgerEntry(transactionId, walletId, accountId, Direction.DEBIT, 2_500, "USD");
        when(repository.findByTransactionIdOrderByCreatedAtAscIdAsc(transactionId))
                .thenReturn(List.of(debit));

        List<LedgerEntryResponse> entries = controller.entriesForTransaction(transactionId);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(debit.getId());
            assertThat(entry.transactionId()).isEqualTo(transactionId);
            assertThat(entry.walletId()).isEqualTo(walletId);
            assertThat(entry.accountId()).isEqualTo(accountId);
            assertThat(entry.direction()).isEqualTo("DEBIT");
            assertThat(entry.amountMinor()).isEqualTo(2_500);
            assertThat(entry.currency()).isEqualTo("USD");
            assertThat(entry.createdAt()).isEqualTo(debit.getCreatedAt());
        });
    }

    /**
     * Ordering is the repository's job (created_at, then id as a stable tiebreak). This asserts
     * the controller actually asks for the ordered finder rather than the unordered one that also
     * exists on the repository, and passes the rows through in the order it was given them.
     */
    @Test
    void usesTheOrderedFinderAndPreservesItsOrder() {
        UUID transactionId = UUID.randomUUID();
        LedgerEntry debit = new LedgerEntry(transactionId, UUID.randomUUID(), UUID.randomUUID(),
                Direction.DEBIT, 1_000, "USD");
        LedgerEntry credit = new LedgerEntry(transactionId, UUID.randomUUID(), UUID.randomUUID(),
                Direction.CREDIT, 1_000, "USD");
        when(repository.findByTransactionIdOrderByCreatedAtAscIdAsc(transactionId))
                .thenReturn(List.of(debit, credit));

        List<LedgerEntryResponse> entries = controller.entriesForTransaction(transactionId);

        verify(repository).findByTransactionIdOrderByCreatedAtAscIdAsc(transactionId);
        assertThat(entries).extracting(LedgerEntryResponse::id)
                .containsExactly(debit.getId(), credit.getId());
    }

    @Test
    void unknownTransactionYieldsAnEmptyListRatherThanAnError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByTransactionIdOrderByCreatedAtAscIdAsc(unknown)).thenReturn(List.of());

        assertThat(controller.entriesForTransaction(unknown)).isEmpty();
    }
}
