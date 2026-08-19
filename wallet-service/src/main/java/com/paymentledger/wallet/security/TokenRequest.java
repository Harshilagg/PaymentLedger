package com.paymentledger.wallet.security;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Mock auth flow (see SPEC.md non-goals: no real IdP in v1) - trades a bare owner id for a JWT,
 * no credential check.
 */
public record TokenRequest(@NotNull UUID ownerId) {
}
