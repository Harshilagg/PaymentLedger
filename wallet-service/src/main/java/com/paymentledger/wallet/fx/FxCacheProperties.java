package com.paymentledger.wallet.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param enabled whether rate lookups consult Redis at all; disabling falls straight through to the
 *                repository, which is also what happens when Redis is unreachable
 * @param ttl     how long a cached rate is served before being re-read. Bounds staleness: the table
 *                is seeded and static today, but a TTL means a future rate change cannot be served
 *                indefinitely from a cache nobody remembers exists
 */
@ConfigurationProperties(prefix = "app.fx-cache")
public record FxCacheProperties(boolean enabled, Duration ttl) {

    public FxCacheProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("app.fx-cache.ttl must be positive, got " + ttl);
        }
    }
}
