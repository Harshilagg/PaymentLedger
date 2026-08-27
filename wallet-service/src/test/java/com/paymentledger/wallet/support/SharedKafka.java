package com.paymentledger.wallet.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One Kafka for the whole test JVM, on the same never-stopped basis as {@link SharedPostgres} and
 * for the same reason - see the explanation there.
 *
 * Kept in its own class rather than beside Postgres so that JVM class-initialisation laziness does
 * the scheduling for us: only an IT that actually references SharedKafka pays to start a broker.
 * The ITs that need nothing but a database never touch this class.
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
