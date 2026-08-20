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
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferServiceTest {

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);
    private final OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
    private final WalletRepository walletRepository = mock(WalletRepository.class);
    private final TransferService service = new TransferService(
            transactionRepository, outboxEventRepository, walletRepository, new ObjectMapper());

    private Wallet fromWallet;
    private Wallet toWallet;

    @BeforeEach
    void setUp() {
        fromWallet = new Wallet(UUID.randomUUID(), Currency.getInstance("USD"));
        fromWallet.credit(10_000);
        toWallet = new Wallet(UUID.randomUUID(), Currency.getInstance("USD"));
        when(walletRepository.findById(toWallet.getId())).thenReturn(Optional.of(toWallet));
    }

    @Test
    void reservesSourceFundsAndInitiatesTheTransactionAtomically() {
        TransactionResponse response = service.initiateTransfer(
                fromWallet, toWallet.getId(), new BigDecimal("25.00"), "key-1");

        assertThat(fromWallet.getReservedMinor()).isEqualTo(2_500);
        assertThat(response.type()).isEqualTo("TRANSFER");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(walletRepository).save(fromWallet);
        verify(transactionRepository).save(any(Transaction.class));
        verify(outboxEventRepository).save(any());
    }

    @Test
    void insufficientFundsRejectsBeforeAnyPersistenceHappens() {
        assertThatThrownBy(() -> service.initiateTransfer(fromWallet, toWallet.getId(), new BigDecimal("500.00"), "key-2"))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    void destinationWalletMustExist() {
        UUID missingWalletId = UUID.randomUUID();
        when(walletRepository.findById(missingWalletId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateTransfer(fromWallet, missingWalletId, new BigDecimal("10.00"), "key-3"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void cannotTransferAWalletToItself() {
        assertThatThrownBy(() -> service.initiateTransfer(fromWallet, fromWallet.getId(), new BigDecimal("10.00"), "key-4"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void crossCurrencyTransfersAreNotYetSupported() {
        Wallet eurWallet = new Wallet(UUID.randomUUID(), Currency.getInstance("EUR"));
        when(walletRepository.findById(eurWallet.getId())).thenReturn(Optional.of(eurWallet));

        assertThatThrownBy(() -> service.initiateTransfer(fromWallet, eurWallet.getId(), new BigDecimal("10.00"), "key-5"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("422");
    }
}
