package com.paymentledger.wallet.security;

import jakarta.validation.constraints.NotBlank;

/**
 * Deliberately looser than {@link RegisterRequest}: no @Email or @Size here, because a validation
 * error shaped differently from a credential rejection would tell an attacker which of the two
 * they hit. Everything that gets past @NotBlank fails the same way.
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password) {
}
