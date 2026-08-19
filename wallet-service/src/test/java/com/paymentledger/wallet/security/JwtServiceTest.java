package com.paymentledger.wallet.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "test-only-secret-key-must-be-at-least-32-bytes-long", 60);

    @Test
    void issuedTokenParsesBackToTheSameOwnerId() {
        UUID ownerId = UUID.randomUUID();

        String token = jwtService.issueToken(ownerId);

        assertThat(jwtService.parseOwnerId(token)).contains(ownerId);
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issueToken(UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertThat(jwtService.parseOwnerId(tampered)).isEmpty();
    }

    @Test
    void garbageTokenIsRejected() {
        assertThat(jwtService.parseOwnerId("not-a-jwt")).isEmpty();
    }

    @Test
    void tokenSignedWithADifferentKeyIsRejected() {
        JwtService otherService = new JwtService(
                "a-completely-different-test-secret-key-of-32-bytes", 60);
        String token = otherService.issueToken(UUID.randomUUID());

        assertThat(jwtService.parseOwnerId(token)).isEmpty();
    }
}
