package com.paymentledger.wallet.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres for the whole test JVM, started once and deliberately never stopped.
 *
 * The obvious-looking alternative - each IT starting a container in @BeforeAll and stopping it in
 * @AfterAll - is wrong here, and was the cause of a flood of "Connection refused" stack traces
 * during builds. Spring's TestContext framework caches application contexts for the lifetime of
 * the JVM; it does not close a context when its test class finishes. So @AfterAll would stop the
 * database out from under a context that is still very much alive, and that context's @Scheduled
 * OutboxRelay would keep polling every 500ms against a dead socket - each attempt burning a
 * 30-second Hikari timeout - until the JVM finally exited. With several ITs, several such
 * abandoned contexts pile up at once, which is why the logs showed HikariPool-1, -2 and -3 all
 * failing against different ports.
 *
 * Not stopping it is safe: Testcontainers' Ryuk sidecar removes the container when the JVM exits.
 * Sharing it is safe too, because Flyway migrates the schema once and the ITs create their own
 * rows rather than asserting against a fixed dataset.
 */
public final class SharedPostgres {

    private static final PostgreSQLContainer<?> INSTANCE =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        INSTANCE.start();
    }

    private SharedPostgres() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", INSTANCE::getJdbcUrl);
        registry.add("spring.datasource.username", INSTANCE::getUsername);
        registry.add("spring.datasource.password", INSTANCE::getPassword);
    }
}
