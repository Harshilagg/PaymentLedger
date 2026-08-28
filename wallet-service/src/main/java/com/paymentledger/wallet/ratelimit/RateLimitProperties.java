package com.paymentledger.wallet.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled         master switch; disabling removes the filter's effect entirely
 * @param capacity        maximum tokens a single user's bucket holds - the burst they may spend at once
 * @param refillPerSecond tokens added per second - the sustained rate they settle back to
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(boolean enabled, int capacity, double refillPerSecond) {

    public RateLimitProperties {
        if (capacity <= 0) {
            throw new IllegalArgumentException("app.rate-limit.capacity must be positive, got " + capacity);
        }
        if (refillPerSecond <= 0) {
            throw new IllegalArgumentException(
                    "app.rate-limit.refill-per-second must be positive, got " + refillPerSecond);
        }
    }
}
