package com.paymentledger.wallet.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;

/**
 * One Redis for the whole test JVM, started once and never stopped - same rationale as
 * {@link SharedPostgres}: Spring caches application contexts for the life of the JVM, so a
 * container stopped in @AfterAll would be pulled out from under a context that is still running.
 *
 * A plain GenericContainer rather than a Redis-specific module, so no new test dependency is needed.
 */
public final class SharedRedis {

    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> INSTANCE =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(REDIS_PORT);

    static {
        INSTANCE.start();
    }

    private SharedRedis() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", INSTANCE::getHost);
        registry.add("spring.data.redis.port", () -> INSTANCE.getMappedPort(REDIS_PORT));
    }

    public static String host() {
        return INSTANCE.getHost();
    }

    public static int port() {
        return INSTANCE.getMappedPort(REDIS_PORT);
    }

    // Deliberately no stop(): this container is shared by every IT in the JVM, so stopping it to
    // simulate an outage in one test would break every test that ran afterwards. The fail-open path
    // is exercised by pointing a limiter at an unreachable address instead - see RateLimiterIT.
}
