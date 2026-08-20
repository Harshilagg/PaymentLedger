package com.paymentledger.wallet.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.TransactionStatus;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReversalServiceTest {

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final ReversalService service = new ReversalService(
            transactionRepository, outboxEventRepository, walletRepository, new ObjectMapper());

    private Wallet wallet(long balance) {
        Wallet wallet = new Wallet(UUID.randomUUID(), Currency.getInstance("USD"));
        if (balance > 0) {
            wallet.credit(balance);
        }
        return wallet;
    }

    @Test
    void reversingADepositReservesFundsOnTheWalletItCredited() {
        Wallet depositedWallet = wallet(5_000);
        Transaction original = Transaction.initiateDeposit(depositedWallet.getId(), 5_000, "USD", "orig-key");
        original.markCompleted();
        when(transactionRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(transactionRepository.existsByOriginalTransactionIdAndStatusNot(original.getId(), TransactionStatus.FAILED))
                .thenReturn(false);
        when(walletRepository.findById(depositedWallet.getId())).thenReturn(Optional.of(depositedWallet));

        TransactionResponse response = service.initiateReversal(original.getId(), "reversal-key");

        assertThat(response.type()).isEqualTo("REVERSAL");
        assertThat(depositedWallet.getReservedMinor()).isEqualTo(5_000);
        verify(transactionRepository).save(any(Transaction.class));
        verify(outboxEventRepository).save(any());
    }

    @Test
    void reversingAWithdrawalCreditsTheWalletBackWithNoReservation() {
        Wallet debitedWallet = wallet(10_000);
        debitedWallet.reserve(3_000);
        debitedWallet.settleReservedDebit(3_000); // simulate the original withdrawal having settled
        Transaction original = Transaction.initiateWithdrawal(debitedWallet.getId(), 3_000, "USD", "orig-key");
        original.markCompleted();
        when(transactionRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(transactionRepository.existsByOriginalTransactionIdAndStatusNot(original.getId(), TransactionStatus.FAILED))
                .thenReturn(false);
        when(walletRepository.findById(debitedWallet.getId())).thenReturn(Optional.of(debitedWallet));

        TransactionResponse response = service.initiateReversal(original.getId(), "reversal-key");

        assertThat(response.type()).isEqualTo("REVERSAL");
        // Crediting on reversal-of-withdrawal happens at settlement, not initiation - initiation
        // for a credit-only leg never touches the wallet (see WithdrawalService/DepositService).
        assertThat(debitedWallet.getReservedMinor()).isEqualTo(0);
    }

    @Test
    void onlyCompletedTransactionsCanBeReversed() {
        Wallet targetWallet = wallet(5_000);
        Transaction pending = Transaction.initiateDeposit(targetWallet.getId(), 5_000, "USD", "orig-key");
        when(transactionRepository.findById(pending.getId())).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.initiateReversal(pending.getId(), "reversal-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
    }

    @Test
    void cannotReverseAReversal() {
        Transaction reversal = Transaction.initiateReversal(
                null, UUID.randomUUID(), 1_000, "USD", "key", UUID.randomUUID());
        reversal.markCompleted();
        when(transactionRepository.findById(reversal.getId())).thenReturn(Optional.of(reversal));

        assertThatThrownBy(() -> service.initiateReversal(reversal.getId(), "reversal-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
    }

    @Test
    void aTransactionCannotBeReversedTwice() {
        Wallet targetWallet = wallet(5_000);
        Transaction original = Transaction.initiateDeposit(targetWallet.getId(), 5_000, "USD", "orig-key");
        original.markCompleted();
        when(transactionRepository.findById(original.getId())).thenReturn(Optional.of(original));
        when(transactionRepository.existsByOriginalTransactionIdAndStatusNot(original.getId(), TransactionStatus.FAILED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.initiateReversal(original.getId(), "reversal-key"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
    }
}
