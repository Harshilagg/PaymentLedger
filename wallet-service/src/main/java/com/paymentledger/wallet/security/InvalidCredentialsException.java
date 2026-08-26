package com.paymentledger.wallet.security;

/**
 * Every rejected login and every rejected refresh throws this same type with the same message.
 * Callers must not add detail: distinguishing "no such email" from "wrong password", or "unknown
 * refresh token" from "expired refresh token", hands out exactly the information the attempt was
 * trying to discover.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
