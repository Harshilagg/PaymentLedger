package com.paymentledger.wallet.fx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caches exchange rates in Redis, keyed on the currency pair.
 *
 * Written directly against StringRedisTemplate rather than with Spring's @Cacheable because the
 * point of this cache is to find out whether it helps, and hit/miss counts are the measurement.
 * Annotation-driven caching hides them behind a CacheManager unless extra metrics machinery is
 * wired in, which would be more moving parts than the cache itself.
 *
 * Reads and writes are best-effort. A cache that cannot be reached must never stop a transfer, so
 * every Redis failure degrades to a miss and the caller falls through to the database - the same
 * fail-open reasoning as the rate limiter, and for the same reason: correctness lives in Postgres.
 */
@Component
public class ExchangeRateCache {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateCache.class);
    private static final String KEY_PREFIX = "fx:rate:";

    /** How often cumulative hit/miss counts are logged, in lookups. */
    private static final long LOG_EVERY = 100;

    private final StringRedisTemplate redis;
    private final FxCacheProperties properties;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public ExchangeRateCache(StringRedisTemplate redis, FxCacheProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public Optional<BigDecimal> get(String fromCurrency, String toCurrency) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            String cached = redis.opsForValue().get(key(fromCurrency, toCurrency));
            if (cached == null) {
                recordMiss();
                return Optional.empty();
            }
            recordHit();
            return Optional.of(new BigDecimal(cached));
        } catch (RuntimeException e) {
            // RuntimeException already covers NumberFormatException, so a corrupt cached value is
            // handled here too: either way the database is the authority and re-reading it is
            // always correct, just slower.
            errors.incrementAndGet();
            recordMiss();
            return Optional.empty();
        }
    }

    public void put(String fromCurrency, String toCurrency, BigDecimal rate) {
        if (!properties.enabled()) {
            return;
        }
        try {
            redis.opsForValue().set(key(fromCurrency, toCurrency), rate.toPlainString(), properties.ttl());
        } catch (RuntimeException e) {
            // Failing to populate the cache costs a lookup next time and nothing else.
            errors.incrementAndGet();
        }
    }

    private void recordHit() {
        maybeLog(hits.incrementAndGet() + misses.get());
    }

    private void recordMiss() {
        maybeLog(hits.get() + misses.incrementAndGet());
    }

    private void maybeLog(long total) {
        if (total % LOG_EVERY != 0) {
            return;
        }
        long h = hits.get();
        long m = misses.get();
        long lookups = h + m;
        log.info("FX rate cache: {} hits, {} misses ({}% hit rate), {} redis errors",
                h, m, lookups == 0 ? 0 : (h * 100 / lookups), errors.get());
    }

    private static String key(String fromCurrency, String toCurrency) {
        return KEY_PREFIX + fromCurrency + ":" + toCurrency;
    }

    public long hitCount() {
        return hits.get();
    }

    public long missCount() {
        return misses.get();
    }

    public long errorCount() {
        return errors.get();
    }
}
