package com.sporty.jackpot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the Jackpot Service: accepts bets over REST, publishes them to Kafka and processes them
 * (contribution + reward evaluation) from the {@code jackpot-bets} topic.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JackpotServiceApplication {

    /**
     * Starts the application.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(JackpotServiceApplication.class, args);
    }
}
