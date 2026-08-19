package com.paymentledger.wallet.domain;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID walletId, long requestedMinor, long availableMinor) {
        super("Wallet " + walletId + " has " + availableMinor
                + " minor units available, requested " + requestedMinor);
    }
}
