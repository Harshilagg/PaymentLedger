package com.paymentledger.ledger.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres for the whole test JVM, started once and deliberately never stopped.
 *
 * Starting a container in @BeforeAll and stopping it in @AfterAll is wrong here: Spring's
 * TestContext framework caches application contexts for the lifetime of the JVM and does not close
 * one when its test class finishes, so @AfterAll stops the database out from under a context that
 * is still alive and still running its @Scheduled OutboxRelay - which then logs a connection
 * failure every poll, each burning a 30-second Hikari timeout, until the JVM exits.
 *
 * Not stopping it is safe: Testcontainers' Ryuk sidecar removes the container when the JVM exits.
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
