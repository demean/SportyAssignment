package com.sporty.jackpot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Service configuration bound from {@code jackpot.*}; validated at startup (fail fast).
 *
 * @param kafka Kafka topics, publishing and consumer retry settings
 */
@Validated
@ConfigurationProperties("jackpot")
public record JackpotProperties(@Valid @NotNull Kafka kafka) {

    /**
     * Kafka settings.
     *
     * @param betsTopic            topic bets are published to and consumed from
     * @param deadLetterTopic      topic for records that cannot be processed
     * @param partitions           partitions of the bets topic
     * @param deadLetterPartitions partitions of the dead-letter topic
     * @param replicationFactor    replication factor of both topics
     * @param minInsyncReplicas    {@code min.insync.replicas} of both topics, {@code <= replicationFactor}
     * @param deadLetterRetention  retention of the dead-letter topic
     * @param publishTimeout       how long the API waits for the broker acknowledgement
     * @param consumer             consumer settings
     */
    public record Kafka(@NotBlank String betsTopic,
                        @NotBlank String deadLetterTopic,
                        @Min(1) int partitions,
                        @Min(1) int deadLetterPartitions,
                        @Min(1) short replicationFactor,
                        @Min(1) int minInsyncReplicas,
                        @NotNull Duration deadLetterRetention,
                        @NotNull Duration publishTimeout,
                        @Valid @NotNull Consumer consumer) {

        public Kafka {
            if (minInsyncReplicas > replicationFactor) {
                throw new IllegalArgumentException("jackpot.kafka.min-insync-replicas (" + minInsyncReplicas
                        + ") must not exceed jackpot.kafka.replication-factor (" + replicationFactor + ")");
            }
        }
    }

    /**
     * Consumer settings.
     *
     * @param retry retry/back-off settings of the listener error handler
     */
    public record Consumer(@Valid @NotNull Retry retry) {
    }

    /**
     * Exponential back-off of the listener error handler. {@code maxRetries} bounds retries of unknown failures;
     * transient failures are retried indefinitely with the same intervals.
     *
     * @param maxRetries      retries before an unknown failure is dead-lettered
     * @param initialInterval first back-off interval
     * @param multiplier      back-off multiplier, {@code >= 1.0}
     * @param maxInterval     back-off interval cap
     */
    public record Retry(@Min(0) int maxRetries,
                        @NotNull Duration initialInterval,
                        @DecimalMin("1.0") double multiplier,
                        @NotNull Duration maxInterval) {
    }
}
