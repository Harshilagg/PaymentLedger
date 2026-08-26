package com.paymentledger.wallet.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "test-only-secret-key-must-be-at-least-32-bytes-long";

    private final JwtService jwtService = new JwtService(SECRET, 15);

    @Test
    void issuedTokenParsesBackToTheSameUserId() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.issueAccessToken(userId);

        assertThat(jwtService.parseUserId(token)).contains(userId);
    }

    @Test
    void issuedTokenCarriesJtiAndIat() {
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(jwtService.issueAccessToken(UUID.randomUUID()))
                .getPayload();

        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    void eachIssuedTokenGetsItsOwnJti() {
        UUID userId = UUID.randomUUID();

        assertThat(jtiOf(jwtService.issueAccessToken(userId)))
                .isNotEqualTo(jtiOf(jwtService.issueAccessToken(userId)));
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService alreadyExpired = new JwtService(SECRET, -1);

        String token = alreadyExpired.issueAccessToken(UUID.randomUUID());

        assertThat(jwtService.parseUserId(token)).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issueAccessToken(UUID.randomUUID());
        String tampered = token.substring(0, token.length() - 1)
                + (token.endsWith("A") ? "B" : "A");

        assertThat(jwtService.parseUserId(tampered)).isEmpty();
    }

    @Test
    void garbageTokenIsRejected() {
        assertThat(jwtService.parseUserId("not-a-jwt")).isEmpty();
    }

    @Test
    void tokenSignedWithADifferentKeyIsRejected() {
        JwtService otherService = new JwtService(
                "a-completely-different-test-secret-key-of-32-bytes", 15);
        String token = otherService.issueAccessToken(UUID.randomUUID());

        assertThat(jwtService.parseUserId(token)).isEmpty();
    }

    private static String jtiOf(String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getId();
    }
}
