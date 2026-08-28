package com.paymentledger.wallet.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token bucket per authenticated user, evaluated by a Lua script inside Redis.
 *
 * The script exists for atomicity: see token-bucket.lua for why a read-modify-write from Java would
 * defeat the purpose.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** A Redis outage must not also produce a log line per request. */
    private static final long OUTAGE_LOG_INTERVAL_MILLIS = 30_000;

    private static final String KEY_PREFIX = "ratelimit:user:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final RedisScript<List> script;
    private final AtomicLong lastOutageLogAt = new AtomicLong(0);

    public RateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;

        DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
        loaded.setLocation(new ClassPathResource("ratelimit/token-bucket.lua"));
        loaded.setResultType(List.class);
        this.script = loaded;
    }

    public RateLimitDecision check(String userId) {
        return tryConsume(KEY_PREFIX + userId, 1);
    }

    private RateLimitDecision tryConsume(String key, int tokens) {
        try {
            List<?> result = redis.execute(
                    script,
                    List.of(key),
                    String.valueOf(properties.capacity()),
                    String.valueOf(properties.refillPerSecond()),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(tokens));

            if (result == null || result.size() < 4) {
                // A malformed reply means the script did not run as expected. Treated as an outage
                // rather than silently denying, for the same reason as the catch below.
                return degrade(new IllegalStateException("unexpected script result: " + result));
            }

            return RateLimitDecision.of(
                    asLong(result.get(0)) == 1L,
                    asLong(result.get(1)),
                    asLong(result.get(2)),
                    asLong(result.get(3)));

        } catch (RuntimeException e) {
            return degrade(e);
        }
    }

    /**
     * Redis is unreachable. FAIL OPEN: allow the request.
     *
     * The reasoning, because this is the kind of decision that gets reversed by someone who assumes
     * it was an oversight:
     *
     * Every correctness guarantee in this system lives in Postgres - no-overdraft via balance
     * reservation and @Version, exactly-once posting via idempotency keys and the ledger's unique
     * constraint, saga compensation on failure. None of them depend on Redis. The limiter is a
     * protective control, not a correctness control.
     *
     * Failing closed would turn a Redis outage into a total write outage: nobody can move money
     * because a cache is down. That is a worse incident than the abuse the limiter prevents, and it
     * manufactures a new single point of failure in the payment path out of a component that is not
     * otherwise in it. The blast radius of a brief unlimited window is also bounded - idempotency
     * keys still stop duplicate submissions and balances still stop runaway spend.
     *
     * The honest counter-argument: fail-open is the wrong default when the limiter is the only thing
     * standing between an attacker and an unbounded resource. That is not this bucket. It covers
     * authenticated writes only, and /auth/** is explicitly NOT rate limited here - so nothing in
     * this class should be mistaken for protection against credential stuffing. If that protection
     * is added later it should be a separate control with its own failure policy, quite possibly
     * fail-closed.
     *
     * Logged at WARN and throttled, so the outage is alertable without the log becoming the outage.
     */
    private RateLimitDecision degrade(RuntimeException cause) {
        long now = System.currentTimeMillis();
        long last = lastOutageLogAt.get();
        if (now - last > OUTAGE_LOG_INTERVAL_MILLIS && lastOutageLogAt.compareAndSet(last, now)) {
            log.warn("Rate limiting is DEGRADED - Redis unreachable, requests are being allowed "
                    + "through unlimited (fail-open by design, see RateLimiter). Cause: {}",
                    cause.toString());
        }
        return RateLimitDecision.degraded(properties.capacity());
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    public int capacity() {
        return properties.capacity();
    }
}
