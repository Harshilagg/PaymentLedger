package com.paymentledger.wallet.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Holds only the sha-256 digest of the token it stands for - see V5 migration. Rows are never
 * deleted on use, only revoked, because a revoked row that comes back is the signal that a stolen
 * token is being replayed (see AuthService#refresh).
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RefreshToken(UUID userId, String tokenHash, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.revoked = false;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public void revoke() {
        this.revoked = true;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
