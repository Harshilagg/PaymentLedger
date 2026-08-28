package com.paymentledger.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// @ConfigurationPropertiesScan because @SpringBootApplication alone does not register
// @ConfigurationProperties records as beans - they are only bound when scanned or explicitly
// enabled. Scanning keeps each properties record registered next to the code that owns it.
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WalletServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
