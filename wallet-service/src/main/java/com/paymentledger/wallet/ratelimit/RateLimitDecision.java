package com.paymentledger.wallet.ratelimit;

/**
 * The outcome of one bucket check.
 *
 * @param allowed              whether the request may proceed
 * @param remaining            tokens left after this request
 * @param millisUntilNextToken how long until one token is available - drives Retry-After
 * @param millisUntilFull      how long until the bucket is full again - drives X-RateLimit-Reset
 * @param degraded             true when Redis could not be reached and the request was allowed
 *                             through anyway; the headers on a degraded decision are placeholders
 *                             and must not be presented as a real accounting of the bucket
 */
public record RateLimitDecision(
        boolean allowed,
        long remaining,
        long millisUntilNextToken,
        long millisUntilFull,
        boolean degraded) {

    static RateLimitDecision of(boolean allowed, long remaining, long untilNext, long untilFull) {
        return new RateLimitDecision(allowed, remaining, untilNext, untilFull, false);
    }

    /** Fail-open result: allowed, with the bucket reported as untouched. See RateLimiter. */
    static RateLimitDecision degraded(int capacity) {
        return new RateLimitDecision(true, capacity, 0, 0, true);
    }
}
