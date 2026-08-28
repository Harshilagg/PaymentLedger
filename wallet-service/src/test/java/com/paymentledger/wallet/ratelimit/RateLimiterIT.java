package com.paymentledger.wallet.ratelimit;

import com.paymentledger.wallet.support.SharedRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the bucket against a real Redis. The race test is the one that matters: it is the only
 * thing that actually demonstrates why the decision lives in a Lua script rather than in Java.
 */
class RateLimiterIT {

    private static final List<LettuceConnectionFactory> FACTORIES = new java.util.ArrayList<>();
    private static StringRedisTemplate redisTemplate;

    private RateLimiter limiter;

    private static StringRedisTemplate templateFor(String host, int port) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                // Bounded so the fail-open test fails fast instead of hanging on a dead address.
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

    private RateLimiter limiterWith(int capacity, double refillPerSecond) {
        return new RateLimiter(redisTemplate, new RateLimitProperties(true, capacity, refillPerSecond));
    }

    @Test
    void requestsUnderTheLimitAreAllowedAndReportRemainingCorrectly() {
        limiter = limiterWith(5, 1);
        String user = UUID.randomUUID().toString();

        for (int i = 1; i <= 5; i++) {
            RateLimitDecision decision = limiter.check(user);
            assertThat(decision.allowed()).as("request %d of 5", i).isTrue();
            assertThat(decision.remaining()).isEqualTo(5 - i);
            assertThat(decision.degraded()).isFalse();
        }
    }

    @Test
    void theRequestThatExceedsCapacityIsRejectedAndCarriesRetryTiming() {
        limiter = limiterWith(3, 1);
        String user = UUID.randomUUID().toString();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check(user).allowed()).isTrue();
        }

        RateLimitDecision rejected = limiter.check(user);

        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        // A rejection must say when to come back, or a client can only guess and hammer.
        assertThat(rejected.millisUntilNextToken()).isPositive();
        assertThat(rejected.millisUntilFull()).isGreaterThanOrEqualTo(rejected.millisUntilNextToken());
    }

    @Test
    void bucketRefillsOverTime() throws InterruptedException {
        // 20 tokens/sec: one token back every 50ms, so this waits a fraction of a second, not seconds.
        limiter = limiterWith(2, 20);
        String user = UUID.randomUUID().toString();

        assertThat(limiter.check(user).allowed()).isTrue();
        assertThat(limiter.check(user).allowed()).isTrue();
        assertThat(limiter.check(user).allowed()).as("bucket should be empty").isFalse();

        Thread.sleep(300);

        assertThat(limiter.check(user).allowed()).as("bucket should have refilled").isTrue();
    }

    /**
     * The reason the bucket is a Lua script.
     *
     * Fires far more concurrent requests than the bucket holds, all against one key. A
     * read-modify-write from Java would let several threads read the same remaining count and all
     * decide they were under the limit. Exactly `capacity` must be allowed - no more, and no fewer.
     */
    @Test
    void concurrentRequestsCannotExceedCapacityThroughARace() throws Exception {
        int capacity = 20;
        int attempts = 200;
        // Refill slow enough that nothing meaningful is added back during the test, so the expected
        // total is exactly the capacity rather than capacity plus an unpredictable refill.
        limiter = limiterWith(capacity, 0.001);
        String user = UUID.randomUUID().toString();

        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicInteger allowed = new AtomicInteger();
        try {
            List<Callable<Void>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                tasks.add(() -> {
                    if (limiter.check(user).allowed()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(allowed.get())
                .as("exactly capacity allowed out of %d concurrent attempts", attempts)
                .isEqualTo(capacity);
    }

    @Test
    void bucketsAreIsolatedPerUser() {
        limiter = limiterWith(1, 0.001);
        String userA = UUID.randomUUID().toString();
        String userB = UUID.randomUUID().toString();

        assertThat(limiter.check(userA).allowed()).isTrue();
        assertThat(limiter.check(userA).allowed()).as("A is now exhausted").isFalse();
        assertThat(limiter.check(userB).allowed()).as("B must be unaffected by A").isTrue();
    }

    /**
     * Redis unreachable: the request is ALLOWED and flagged degraded. See the reasoning on
     * RateLimiter#degrade - a rate limiter outage must not become a payments outage.
     *
     * Uses a deliberately dead address rather than stopping the shared container, which every other
     * test in this JVM is still using.
     */
    @Test
    void failsOpenWhenRedisIsUnreachable() {
        StringRedisTemplate dead = templateFor("127.0.0.1", 1); // nothing listens on port 1
        RateLimiter degradedLimiter =
                new RateLimiter(dead, new RateLimitProperties(true, 5, 1));

        RateLimitDecision decision = degradedLimiter.check(UUID.randomUUID().toString());

        assertThat(decision.allowed()).as("fail open, not closed").isTrue();
        assertThat(decision.degraded()).isTrue();
    }
}
