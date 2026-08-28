package com.paymentledger.wallet.fx;

import com.paymentledger.wallet.support.SharedRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The cache against a real Redis, including what it must do when Redis is not there. */
class ExchangeRateCacheIT {

    private static final List<LettuceConnectionFactory> FACTORIES = new java.util.ArrayList<>();
    private static StringRedisTemplate redisTemplate;

    private static StringRedisTemplate templateFor(String host, int port) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ZERO)
                .build();
        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfig);
        factory.afterPropertiesSet();
        FACTORIES.add(factory);
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    @BeforeEach
    void setUp() {
        if (redisTemplate == null) {
            redisTemplate = templateFor(SharedRedis.host(), SharedRedis.port());
        }
    }

    @AfterAll
    static void closeConnections() {
        FACTORIES.forEach(LettuceConnectionFactory::destroy);
        FACTORIES.clear();
        redisTemplate = null;
    }

    private ExchangeRateCache cacheWith(boolean enabled, Duration ttl) {
        return new ExchangeRateCache(redisTemplate, new FxCacheProperties(enabled, ttl));
    }

    @Test
    void storesAndReturnsARateWithFullPrecision() {
        ExchangeRateCache cache = cacheWith(true, Duration.ofMinutes(10));
        BigDecimal rate = new BigDecimal("0.92000000");

        assertThat(cache.get("USD", "EUR")).as("cold").isEmpty();
        cache.put("USD", "EUR", rate);

        // Scale included: 0.92000000 must not come back as 0.92, or a round trip through the cache
        // would silently change the arithmetic the conversion depends on.
        assertThat(cache.get("USD", "EUR"))
                .isPresent()
                .hasValueSatisfying(v -> assertThat(v.toPlainString()).isEqualTo("0.92000000"));
    }

    @Test
    void countsHitsAndMissesSoTheEffectIsMeasurable() {
        ExchangeRateCache cache = cacheWith(true, Duration.ofMinutes(10));

        cache.get("EUR", "GBP");                                   // miss
        cache.put("EUR", "GBP", new BigDecimal("0.85870000"));
        cache.get("EUR", "GBP");                                   // hit
        cache.get("EUR", "GBP");                                   // hit

        assertThat(cache.hitCount()).isEqualTo(2);
        assertThat(cache.missCount()).isEqualTo(1);
        assertThat(cache.errorCount()).isZero();
    }

    @Test
    void pairsAreKeyedIndependentlyAndDirectionally() {
        ExchangeRateCache cache = cacheWith(true, Duration.ofMinutes(10));
        cache.put("GBP", "USD", new BigDecimal("1.26580000"));

        assertThat(cache.get("GBP", "USD")).isPresent();
        // USD->GBP is a different rate, not the inverse of this one, so it must be a different key.
        assertThat(cache.get("USD", "GBP")).isEmpty();
    }

    @Test
    void entriesExpireAfterTheConfiguredTtl() throws InterruptedException {
        ExchangeRateCache cache = cacheWith(true, Duration.ofMillis(300));
        cache.put("USD", "EUR", new BigDecimal("0.92000000"));
        assertThat(cache.get("USD", "EUR")).isPresent();

        Thread.sleep(600);

        assertThat(cache.get("USD", "EUR")).as("should have expired").isEmpty();
    }

    @Test
    void disabledCacheNeverReportsAHit() {
        ExchangeRateCache cache = cacheWith(false, Duration.ofMinutes(10));
        cache.put("USD", "EUR", new BigDecimal("0.92000000"));

        assertThat(cache.get("USD", "EUR")).isEmpty();
        assertThat(cache.hitCount()).isZero();
    }

    /**
     * An unreachable Redis degrades to a miss so the caller falls through to the database. A cache
     * outage must never stop a transfer - the same fail-open reasoning as the rate limiter.
     */
    @Test
    void unreachableRedisDegradesToAMissRatherThanFailing() {
        ExchangeRateCache cache = new ExchangeRateCache(
                templateFor("127.0.0.1", 1), new FxCacheProperties(true, Duration.ofMinutes(10)));

        Optional<BigDecimal> result = cache.get("USD", "EUR");

        assertThat(result).isEmpty();
        assertThat(cache.errorCount()).isPositive();
        // And a failed write must not propagate either.
        cache.put("USD", "EUR", new BigDecimal("0.92000000"));
    }
}
