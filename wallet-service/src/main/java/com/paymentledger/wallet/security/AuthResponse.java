package com.paymentledger.wallet.security;

import java.util.UUID;

/**
 * userId is the same value as the access token's sub claim, returned explicitly so clients can
 * identify the signed-in user without parsing a token they are only meant to relay.
 */
public record AuthResponse(String accessToken, String refreshToken, UUID userId) {
}
