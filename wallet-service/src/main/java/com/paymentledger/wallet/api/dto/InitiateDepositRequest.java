package com.paymentledger.wallet.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record InitiateDepositRequest(@NotNull @DecimalMin(value = "0.00", inclusive = false) BigDecimal amount) {
}
