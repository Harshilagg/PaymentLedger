package com.paymentledger.wallet.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Reads the owner id JwtAuthenticationFilter placed on the security context as principal. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID ownerId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return (UUID) principal;
    }
}
