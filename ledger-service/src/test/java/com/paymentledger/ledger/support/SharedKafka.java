package com.paymentledger.ledger.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Kafka for the whole test JVM, on the same never-stopped basis as {@link SharedPostgres} and
 * for the same reason - see the explanation there.
 *
 * Kept in its own class so JVM class-initialisation laziness means only an IT that actually
 * references it pays to start a broker.
 *
 * org.testcontainers.containers.KafkaContainer (the older class) is hard-wired to Confluent's
 * image and its ports; org.testcontainers.kafka.KafkaContainer is the one built for the official
 * apache/kafka image this project runs.
 */
public final class SharedKafka {

    private static final KafkaContainer INSTANCE =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.7.0"));

    static {
        INSTANCE.start();
    }

    private SharedKafka() {
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", INSTANCE::getBootstrapServers);
    }
}
