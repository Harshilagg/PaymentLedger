package com.paymentledger.wallet.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentledger.wallet.api.dto.TransactionResponse;
import com.paymentledger.wallet.domain.InsufficientFundsException;
import com.paymentledger.wallet.domain.Transaction;
import com.paymentledger.wallet.domain.TransactionRepository;
import com.paymentledger.wallet.domain.Wallet;
import com.paymentledger.wallet.domain.WalletRepository;
import com.paymentledger.wallet.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class WithdrawalServiceTest {

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final WithdrawalService service = new WithdrawalService(
            transactionRepository, outboxEventRepository, walletRepository, new ObjectMapper());

    private Wallet wallet;

    @BeforeEach
    void setUp() {
        wallet = new Wallet(java.util.UUID.randomUUID(), Currency.getInstance("USD"));
        wallet.credit(10_000);
    }

    @Test
    void reservesFundsAndInitiatesTheTransactionAtomically() {
        TransactionResponse response = service.initiateWithdrawal(wallet, new BigDecimal("40.00"), "key-1");

        assertThat(wallet.getReservedMinor()).isEqualTo(4_000);
        assertThat(wallet.getBalanceMinor()).isEqualTo(10_000);
        assertThat(response.type()).isEqualTo("WITHDRAWAL");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(walletRepository).save(wallet);
        verify(transactionRepository).save(any(Transaction.class));
        verify(outboxEventRepository).save(any());
    }

    @Test
    void insufficientFundsRejectsBeforeAnyPersistenceHappens() {
        assertThatThrownBy(() -> service.initiateWithdrawal(wallet, new BigDecimal("500.00"), "key-2"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(walletRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }
}
