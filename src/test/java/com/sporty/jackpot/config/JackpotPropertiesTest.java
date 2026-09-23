package com.sporty.jackpot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

@DisplayName("JackpotProperties (jackpot.*)")
class JackpotPropertiesTest {

    private static final String PREFIX = "jackpot.kafka.";

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JackpotProperties.class)
    static class PropertiesConfiguration {
    }

    private static Map<String, String> validProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PREFIX + "bets-topic", "bets");
        properties.put(PREFIX + "dead-letter-topic", "bets.DLT");
        properties.put(PREFIX + "partitions", "24");
        properties.put(PREFIX + "dead-letter-partitions", "6");
        properties.put(PREFIX + "replication-factor", "3");
        properties.put(PREFIX + "min-insync-replicas", "2");
        properties.put(PREFIX + "dead-letter-retention", "30d");
        properties.put(PREFIX + "publish-timeout", "12s");
        properties.put(PREFIX + "consumer.retry.max-retries", "5");
        properties.put(PREFIX + "consumer.retry.initial-interval", "500ms");
        properties.put(PREFIX + "consumer.retry.multiplier", "1.5");
        properties.put(PREFIX + "consumer.retry.max-interval", "30s");
        return properties;
    }

    /** Registers the properties verbatim (unlike {@code withPropertyValues}, values are not trimmed). */
    private static ApplicationContextRunner runnerWith(Map<String, String> properties) {
        Map<String, Object> source = new LinkedHashMap<>(properties);
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("jackpot-test-properties", source)))
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class);
    }

    private static Consumer<Map<String, String>> set(String key, String value) {
        return properties -> properties.put(PREFIX + key, value);
    }

    private static Consumer<Map<String, String>> unset(String key) {
        return properties -> assertThat(properties.remove(PREFIX + key)).as("valid property " + key).isNotNull();
    }

    @Test
    @DisplayName("binds every property, including durations, short and double values")
    void bindsAllProperties() {
        runnerWith(validProperties()).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JackpotProperties.class);
            JackpotProperties.Kafka kafka = context.getBean(JackpotProperties.class).kafka();

            assertThat(kafka.betsTopic()).isEqualTo("bets");
            assertThat(kafka.deadLetterTopic()).isEqualTo("bets.DLT");
            assertThat(kafka.partitions()).isEqualTo(24);
            assertThat(kafka.deadLetterPartitions()).isEqualTo(6);
            assertThat(kafka.replicationFactor()).isEqualTo((short) 3);
            assertThat(kafka.minInsyncReplicas()).isEqualTo(2);
            assertThat(kafka.deadLetterRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(kafka.publishTimeout()).isEqualTo(Duration.ofSeconds(12));
            JackpotProperties.Retry retry = kafka.consumer().retry();
            assertThat(retry.maxRetries()).isEqualTo(5);
            assertThat(retry.initialInterval()).isEqualTo(Duration.ofMillis(500));
            assertThat(retry.multiplier()).isEqualTo(1.5);
            assertThat(retry.maxInterval()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    @DisplayName("the shipped application.yml binds and passes validation")
    void shippedConfigurationIsValid() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JackpotProperties.Kafka kafka = context.getBean(JackpotProperties.class).kafka();

                    assertThat(kafka.betsTopic()).isEqualTo("jackpot-bets");
                    assertThat(kafka.deadLetterTopic()).isEqualTo("jackpot-bets.DLT");
                    assertThat(kafka.partitions()).isEqualTo(12);
                    assertThat(kafka.deadLetterPartitions()).isEqualTo(3);
                    assertThat(kafka.replicationFactor()).isEqualTo((short) 1);
                    assertThat(kafka.minInsyncReplicas()).isEqualTo(1);
                    assertThat(kafka.deadLetterRetention()).isEqualTo(Duration.ofDays(30));
                    assertThat(kafka.publishTimeout()).isEqualTo(Duration.ofSeconds(12));
                    assertThat(kafka.consumer().retry()).isNotNull();
                });
    }

    @Nested
    @DisplayName("fails the startup")
    class Invalid {

        static Stream<Arguments> invalidProperties() {
            return Stream.of(
                    Arguments.of("empty bets topic", set("bets-topic", ""), List.of("kafka.betsTopic")),
                    Arguments.of("whitespace-only bets topic", set("bets-topic", " \t "), List.of("kafka.betsTopic")),
                    Arguments.of("missing bets topic", unset("bets-topic"), List.of("kafka.betsTopic")),
                    Arguments.of("empty dead-letter topic", set("dead-letter-topic", ""),
                            List.of("kafka.deadLetterTopic")),
                    Arguments.of("missing dead-letter topic", unset("dead-letter-topic"),
                            List.of("kafka.deadLetterTopic")),
                    Arguments.of("0 partitions", set("partitions", "0"), List.of("kafka.partitions")),
                    Arguments.of("missing partitions (int defaults to 0)", unset("partitions"),
                            List.of("kafka.partitions")),
                    Arguments.of("0 dead-letter partitions", set("dead-letter-partitions", "0"),
                            List.of("kafka.deadLetterPartitions")),
                    Arguments.of("replication factor and min ISR 0",
                            set("replication-factor", "0").andThen(set("min-insync-replicas", "0")),
                            List.of("kafka.replicationFactor", "kafka.minInsyncReplicas")),
                    Arguments.of("min ISR 0", set("min-insync-replicas", "0"), List.of("kafka.minInsyncReplicas")),
                    Arguments.of("missing dead-letter retention", unset("dead-letter-retention"),
                            List.of("kafka.deadLetterRetention")),
                    Arguments.of("empty dead-letter retention", set("dead-letter-retention", ""),
                            List.of("kafka.deadLetterRetention")),
                    Arguments.of("missing publish timeout", unset("publish-timeout"), List.of("kafka.publishTimeout")),
                    Arguments.of("negative max retries", set("consumer.retry.max-retries", "-1"),
                            List.of("kafka.consumer.retry.maxRetries")),
                    Arguments.of("missing initial interval", unset("consumer.retry.initial-interval"),
                            List.of("kafka.consumer.retry.initialInterval")),
                    Arguments.of("multiplier below 1.0", set("consumer.retry.multiplier", "0.99"),
                            List.of("kafka.consumer.retry.multiplier")),
                    Arguments.of("missing max interval", unset("consumer.retry.max-interval"),
                            List.of("kafka.consumer.retry.maxInterval")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidProperties")
        void onConstraintViolations(String description, Consumer<Map<String, String>> change,
                                    List<String> expectedFields) {
            Map<String, String> properties = validProperties();
            change.accept(properties);

            runnerWith(properties).run(context ->
                    assertThat(violatedFields(context.getStartupFailure()))
                            .containsExactlyInAnyOrderElementsOf(expectedFields));
        }

        @Test
        @DisplayName("when the whole consumer retry block is missing")
        void missingConsumer() {
            Map<String, String> properties = validProperties();
            properties.keySet().removeIf(key -> key.startsWith(PREFIX + "consumer."));

            runnerWith(properties).run(context ->
                    assertThat(violatedFields(context.getStartupFailure())).containsExactly("kafka.consumer"));
        }

        @Test
        @DisplayName("when no jackpot.* property is set at all")
        void missingKafka() {
            runnerWith(Map.of()).run(context ->
                    assertThat(violatedFields(context.getStartupFailure())).containsExactly("kafka"));
        }

        @Test
        @DisplayName("when min.insync.replicas exceeds the replication factor")
        void minInsyncReplicasAboveReplicationFactor() {
            Map<String, String> properties = validProperties();
            properties.put(PREFIX + "replication-factor", "1");
            properties.put(PREFIX + "min-insync-replicas", "2");

            runnerWith(properties).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).isInstanceOf(ConfigurationPropertiesBindException.class);
                assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("jackpot.kafka.min-insync-replicas (2) must not exceed "
                                + "jackpot.kafka.replication-factor (1)");
            });
        }

        private static List<String> violatedFields(Throwable startupFailure) {
            assertThat(startupFailure).isInstanceOf(ConfigurationPropertiesBindException.class);
            Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(startupFailure);
            assertThat(rootCause).isInstanceOf(BindValidationException.class);
            List<ObjectError> errors = ((BindValidationException) rootCause).getValidationErrors().getAllErrors();
            assertThat(errors).allSatisfy(error -> assertThat(error).isInstanceOf(FieldError.class));
            return errors.stream().map(error -> ((FieldError) error).getField()).toList();
        }
    }

    @Test
    @DisplayName("accepts the smallest valid values (1 partition, 0 retries, multiplier 1.0)")
    void acceptsLowerBounds() {
        Map<String, String> properties = validProperties();
        List.of(set("partitions", "1"), set("dead-letter-partitions", "1"), set("replication-factor", "1"),
                        set("min-insync-replicas", "1"), set("consumer.retry.max-retries", "0"),
                        set("consumer.retry.multiplier", "1.0"))
                .forEach(change -> change.accept(properties));

        runnerWith(properties).run(context -> {
            assertThat(context).hasNotFailed();
            JackpotProperties.Kafka kafka = context.getBean(JackpotProperties.class).kafka();
            assertThat(kafka.partitions()).isEqualTo(1);
            assertThat(kafka.consumer().retry().maxRetries()).isZero();
            assertThat(kafka.consumer().retry().multiplier()).isEqualTo(1.0);
        });
    }

    @Nested
    @DisplayName("min.insync.replicas vs replication factor")
    class MinInsyncReplicas {

        @ParameterizedTest(name = "replication factor {0}, min ISR {1}")
        @CsvSource({"1, 1", "3, 1", "3, 2", "3, 3"})
        void acceptsMinInsyncReplicasUpToTheReplicationFactor(short replicationFactor, int minInsyncReplicas) {
            JackpotProperties.Kafka kafka = kafka(replicationFactor, minInsyncReplicas, retry());

            assertThat(kafka.minInsyncReplicas()).isLessThanOrEqualTo(kafka.replicationFactor());
        }

        @ParameterizedTest(name = "replication factor {0}, min ISR {1}")
        @CsvSource({"1, 2", "3, 4"})
        void rejectsMinInsyncReplicasAboveTheReplicationFactor(short replicationFactor, int minInsyncReplicas) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> kafka(replicationFactor, minInsyncReplicas, retry()))
                    .withMessage("jackpot.kafka.min-insync-replicas (" + minInsyncReplicas
                            + ") must not exceed jackpot.kafka.replication-factor (" + replicationFactor + ")");
        }
    }

    @Test
    @DisplayName("validation cascades into nested records (consumer.retry must be present)")
    void cascadesIntoNestedRecords() {
        JackpotProperties properties = new JackpotProperties(
                new JackpotProperties.Kafka("bets", "bets.DLT", 12, 3, (short) 1, 1, Duration.ofDays(30),
                        Duration.ofSeconds(12), new JackpotProperties.Consumer(null)));

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<ConstraintViolation<JackpotProperties>> violations = validator.validate(properties);

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .containsExactly("kafka.consumer.retry");
            assertThat(validator.validate(new JackpotProperties(kafka((short) 1, 1, retry())))).isEmpty();
        }
    }

    private static JackpotProperties.Retry retry() {
        return new JackpotProperties.Retry(5, Duration.ofMillis(500), 2.0, Duration.ofSeconds(30));
    }

    private static JackpotProperties.Kafka kafka(short replicationFactor, int minInsyncReplicas,
                                                 JackpotProperties.Retry retry) {
        return new JackpotProperties.Kafka("bets", "bets.DLT", 12, 3, replicationFactor, minInsyncReplicas,
                Duration.ofDays(30), Duration.ofSeconds(12), new JackpotProperties.Consumer(retry));
    }
}
