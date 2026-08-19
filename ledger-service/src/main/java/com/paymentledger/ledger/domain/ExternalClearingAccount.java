package com.paymentledger.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deposits and withdrawals move money between a wallet and the outside world, but double-entry
 * bookkeeping requires two balanced rows per transaction (see SPEC.md data model). The
 * counterparty is this deterministic, well-known "external cash" wallet/account per currency -
 * the same clearing-account pattern SPEC.md already uses for cross-currency FX, just applied to
 * the external-money case. It never appears in wallet-service (which only tracks real wallets),
 * only as the second leg of a ledger_entry pair here.
 */
public final class ExternalClearingAccount {

    private ExternalClearingAccount() {
    }

    public static UUID walletIdFor(String currencyCode) {
        return UUID.nameUUIDFromBytes(("external-clearing-wallet:" + currencyCode)
                .getBytes(StandardCharsets.UTF_8));
    }

    public static UUID accountIdFor(String currencyCode) {
        return UUID.nameUUIDFromBytes(("external-clearing-account:" + currencyCode)
                .getBytes(StandardCharsets.UTF_8));
    }
}
