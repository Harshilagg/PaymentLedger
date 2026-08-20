package com.paymentledger.wallet.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record InitiateTransferRequest(
        @NotNull UUID toWalletId,
        @NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount) {
}
