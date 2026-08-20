package com.paymentledger.wallet.transaction;

import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.event.OutcomeStatus;
import com.paymentledger.wallet.event.TransactionOutcomeEvent;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementServiceTest {

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final SettlementService service = new SettlementService(transactionRepository, walletRepository);

    private Wallet walletWith(long balance) {
        Wallet wallet = new Wallet(UUID.randomUUID(), Currency.getInstance("USD"));
        if (balance > 0) {
            wallet.credit(balance);
        }
        return wallet;
    }

    @Test
    void postedDepositCreditsTheDestinationWallet() {
        Wallet toWallet = walletWith(0);
        Transaction transaction = Transaction.initiateDeposit(toWallet.getId(), 5_000, "USD", "key");
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(walletRepository.findById(toWallet.getId())).thenReturn(Optional.of(toWallet));

        service.applyOutcome(new TransactionOutcomeEvent(transaction.getId(), OutcomeStatus.POSTED, null));

        assertThat(toWallet.getBalanceMinor()).isEqualTo(5_000);
        assertThat(transaction.getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    void postedWithdrawalSettlesTheReservedHold() {
        Wallet fromWallet = walletWith(10_000);
        fromWallet.reserve(4_000);
        Transaction transaction = Transaction.initiateWithdrawal(fromWallet.getId(), 4_000, "USD", "key");
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(walletRepository.findById(fromWallet.getId())).thenReturn(Optional.of(fromWallet));

        service.applyOutcome(new TransactionOutcomeEvent(transaction.getId(), OutcomeStatus.POSTED, null));

        assertThat(fromWallet.getBalanceMinor()).isEqualTo(6_000);
        assertThat(fromWallet.getReservedMinor()).isEqualTo(0);
    }

    @Test
    void failedWithdrawalReleasesTheHoldWithoutTouchingSettledBalance() {
        Wallet fromWallet = walletWith(10_000);
        fromWallet.reserve(4_000);
        Transaction transaction = Transaction.initiateWithdrawal(fromWallet.getId(), 4_000, "USD", "key");
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(walletRepository.findById(fromWallet.getId())).thenReturn(Optional.of(fromWallet));

        service.applyOutcome(new TransactionOutcomeEvent(transaction.getId(), OutcomeStatus.FAILED, "wallet frozen"));

        assertThat(fromWallet.getBalanceMinor()).isEqualTo(10_000);
        assertThat(fromWallet.getReservedMinor()).isEqualTo(0);
        assertThat(transaction.getStatus().name()).isEqualTo("FAILED");
        assertThat(transaction.getFailureReason()).isEqualTo("wallet frozen");
    }

    @Test
    void postedTransferSettlesSourceAndCreditsDestination() {
        Wallet fromWallet = walletWith(10_000);
        fromWallet.reserve(3_000);
        Wallet toWallet = walletWith(0);
        Transaction transaction = Transaction.initiateTransfer(
                fromWallet.getId(), toWallet.getId(), 3_000, "USD", "key");
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(walletRepository.findById(fromWallet.getId())).thenReturn(Optional.of(fromWallet));
        when(walletRepository.findById(toWallet.getId())).thenReturn(Optional.of(toWallet));

        service.applyOutcome(new TransactionOutcomeEvent(transaction.getId(), OutcomeStatus.POSTED, null));

        assertThat(fromWallet.getBalanceMinor()).isEqualTo(7_000);
        assertThat(fromWallet.getReservedMinor()).isEqualTo(0);
        assertThat(toWallet.getBalanceMinor()).isEqualTo(3_000);
    }

    @Test
    void failedTransferReleasesTheSourceHoldOnly() {
        Wallet fromWallet = walletWith(10_000);
        fromWallet.reserve(3_000);
        Wallet toWallet = walletWith(0);
        Transaction transaction = Transaction.initiateTransfer(
                fromWallet.getId(), toWallet.getId(), 3_000, "USD", "key");
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));
        when(walletRepository.findById(fromWallet.getId())).thenReturn(Optional.of(fromWallet));

        service.applyOutcome(new TransactionOutcomeEvent(transaction.getId(), OutcomeStatus.FAILED, "destination frozen"));

        assertThat(fromWallet.getBalanceMinor()).isEqualTo(10_000);
        assertThat(fromWallet.getReservedMinor()).isEqualTo(0);
        assertThat(toWallet.getBalanceMinor()).isEqualTo(0);
        verify(walletRepository, never()).findById(toWallet.getId());
    }

    @Test
    void postedReversalOfADepositSettlesTheHoldTakenAtReversalInitiation() {
        // Reversal of a deposit is debit-shaped: fromWalletId set, toWalletId null - the same
        // shape a withdrawal has, so it must settle the same way.
        Wallet wallet = walletWith(5_000);
        wallet.reserve(5_000); // ReversalService already took this hold at initiation
        Transaction reversal = Transaction.initiateReversal(
                wallet.getId(), null, 5_000, "USD", "key", UUID.randomUUID());
        when(transactionRepository.findById(reversal.getId())).thenReturn(Optional.of(reversal));
        when(walletRepository.findById(wallet.getId())).thenReturn(Optional.of(wallet));

        service.applyOutcome(new TransactionOutcomeEvent(reversal.getId(), OutcomeStatus.POSTED, null));

        assertThat(wallet.getBalanceMinor()).isEqualTo(0);
        assertThat(wallet.getReservedMinor()).isEqualTo(0);
    }

    @Test
    void failedDepositNeedsNoWalletMutationSinceNoHoldWasEverTaken() {
        Wallet toWallet = walletWith(0);
        Transaction transaction = Transaction.initiateDeposit(toWallet.getId(), 5_000, "USD", "key");
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        service.applyOutcome(new TransactionOutcomeEvent(transaction.getId(), OutcomeStatus.FAILED, "rejected"));

        verify(walletRepository, never()).findById(toWallet.getId());
        assertThat(transaction.getStatus().name()).isEqualTo("FAILED");
    }

    @Test
    void redeliveredOutcomeForANonPendingTransactionIsANoOp() {
        Wallet toWallet = walletWith(0);
        Transaction transaction = Transaction.initiateDeposit(toWallet.getId(), 5_000, "USD", "key");
        transaction.markCompleted();
        when(transactionRepository.findById(transaction.getId())).thenReturn(Optional.of(transaction));

        service.applyOutcome(new TransactionOutcomeEvent(transaction.getId(), OutcomeStatus.POSTED, null));

        verify(walletRepository, never()).findById(toWallet.getId());
        verify(transactionRepository, never()).save(transaction);
    }
}
