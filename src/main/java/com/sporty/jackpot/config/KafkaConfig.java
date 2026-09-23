package com.sporty.jackpot.config;

import com.sporty.jackpot.exception.InvalidBetException;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import com.sporty.jackpot.messaging.BetPlacedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka wiring on top of Spring Boot's auto-configured producer/consumer/container factories: topics, the value
 * serializer, the listener error handler (retry classification + dead-letter topic) and the listener container
 * customization.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

    static final String DEAD_LETTERED_COUNTER = "jackpot.bets.dead-lettered";

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** Kafka producer defaults, used when {@code max.block.ms} / {@code delivery.timeout.ms} are not configured. */
    private static final long DEFAULT_MAX_BLOCK_MS = 60_000L;
    private static final long DEFAULT_DELIVERY_TIMEOUT_MS = 120_000L;
    private static final Duration PUBLISH_TIMEOUT_MARGIN = Duration.ofSeconds(1);
    private static final Duration CONTAINER_SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_CAUSE_DEPTH = 32;
    private static final List<Class<? extends Throwable>> TRANSIENT_FAILURES = List.of(
            TransientDataAccessException.class,
            RecoverableDataAccessException.class,
            CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class,
            SQLTransientException.class,
            RetriableException.class);

    @Bean
    NewTopic betsTopic(JackpotProperties properties) {
        JackpotProperties.Kafka kafka = properties.kafka();
        return TopicBuilder.name(kafka.betsTopic())
                .partitions(kafka.partitions())
                .replicas(kafka.replicationFactor())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(kafka.minInsyncReplicas()))
                .build();
    }

    @Bean
    NewTopic deadLetterTopic(JackpotProperties properties) {
        JackpotProperties.Kafka kafka = properties.kafka();
        return TopicBuilder.name(kafka.deadLetterTopic())
                .partitions(kafka.deadLetterPartitions())
                .replicas(kafka.replicationFactor())
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(kafka.minInsyncReplicas()))
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(kafka.deadLetterRetention().toMillis()))
                .build();
    }

    /**
     * Keeps Boot's producer factory (all {@code spring.kafka.producer.*} settings) but fails fast on an inconsistent
     * publish timeout and swaps the value serializer for {@link #betValueSerializer()}.
     */
    @Bean
    DefaultKafkaProducerFactoryCustomizer betProducerFactoryCustomizer(JackpotProperties properties) {
        Duration publishTimeout = properties.kafka().publishTimeout();
        return factory -> {
            validatePublishTimeout(publishTimeout, factory.getConfigurationProperties());
            useBetValueSerializer(factory);
        };
    }

    /**
     * Listener error handling: deterministic failures go to the dead-letter topic immediately, transient DB/Kafka
     * failures are retried indefinitely (the partition blocks, nothing is lost), anything else is retried
     * {@code maxRetries} times and then dead-lettered.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations, JackpotProperties properties,
                                         MeterRegistry meterRegistry) {
        JackpotProperties.Kafka kafka = properties.kafka();
        DeadLetterPublishingRecoverer deadLetterPublisher = new DeadLetterPublishingRecoverer(kafkaOperations,
                deadLetterDestination(kafka.deadLetterTopic()));
        JackpotProperties.Retry retry = kafka.consumer().retry();
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                countingRecoverer(deadLetterPublisher, meterRegistry), boundedBackOff(retry));
        errorHandler.addNotRetryableExceptions(InvalidBetException.class, JackpotConfigurationException.class,
                DataIntegrityViolationException.class);
        errorHandler.setBackOffFunction(backOffFunction(unboundedBackOff(retry)));
        return errorHandler;
    }

    /**
     * Listener containers run on platform threads (Kafka poll loops would pin virtual threads on JDK 21), shut down
     * gracefully and route key/value deserialization failures to the error handler.
     */
    @Bean
    ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>> listenerContainerCustomizer() {
        return container -> {
            ContainerProperties containerProperties = container.getContainerProperties();
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("jackpot-kafka-");
            executor.setVirtualThreads(false);
            containerProperties.setListenerTaskExecutor(executor);
            containerProperties.setShutdownTimeout(CONTAINER_SHUTDOWN_TIMEOUT.toMillis());
            containerProperties.setCheckDeserExWhenKeyNull(true);
            containerProperties.setCheckDeserExWhenValueNull(true);
        };
    }

    /**
     * Value serializer of the producer: raw {@code byte[]} pass through (dead-lettered poison pills keep their
     * original bytes), {@link BetPlacedEvent}s are written as JSON without type headers.
     */
    static Serializer<Object> betValueSerializer() {
        Map<Class<?>, Serializer<?>> delegates = new LinkedHashMap<>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(BetPlacedEvent.class, new JacksonJsonSerializer<BetPlacedEvent>().noTypeInfo());
        return new DelegatingByTypeSerializer(delegates, true);
    }

    /**
     * Fails fast unless {@code publishTimeout >= max.block.ms + delivery.timeout.ms + 1s}, so the API never gives up
     * on a send the producer may still complete.
     */
    static void validatePublishTimeout(Duration publishTimeout, Map<String, Object> producerConfig) {
        Duration required = Duration.ofMillis(
                        longConfig(producerConfig, ProducerConfig.MAX_BLOCK_MS_CONFIG, DEFAULT_MAX_BLOCK_MS)
                                + longConfig(producerConfig, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                                DEFAULT_DELIVERY_TIMEOUT_MS))
                .plus(PUBLISH_TIMEOUT_MARGIN);
        if (publishTimeout.compareTo(required) < 0) {
            throw new IllegalStateException("jackpot.kafka.publish-timeout (" + publishTimeout
                    + ") must be >= max.block.ms + delivery.timeout.ms + 1s (" + required + ")");
        }
    }

    /** Dead-letter destination: the configured topic, partition chosen by the producer. */
    static BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> deadLetterDestination(String deadLetterTopic) {
        return (consumerRecord, exception) -> new TopicPartition(deadLetterTopic, -1);
    }

    /**
     * Logs every record before delegating and counts it ({@value #DEAD_LETTERED_COUNTER}) once the delegate has
     * dead-lettered it; a failed dead-letter publication propagates (the record is retried) and is not counted.
     */
    static ConsumerRecordRecoverer countingRecoverer(ConsumerRecordRecoverer delegate, MeterRegistry meterRegistry) {
        return (consumerRecord, exception) -> {
            Throwable rootCause = NestedExceptionUtils.getMostSpecificCause(exception);
            log.error("Dead-lettering record {}-{}@{} (key={}): {}", consumerRecord.topic(), consumerRecord.partition(),
                    consumerRecord.offset(), consumerRecord.key(), rootCause.toString());
            delegate.accept(consumerRecord, exception);
            meterRegistry.counter(DEAD_LETTERED_COUNTER, "exception", rootCause.getClass().getSimpleName()).increment();
        };
    }

    /** Transient failures get the unbounded back-off; {@code null} selects the handler's bounded default. */
    static BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> backOffFunction(BackOff transientBackOff) {
        return (consumerRecord, exception) -> isTransient(exception) ? transientBackOff : null;
    }

    /** Whether the cause chain contains a transient (retry-until-it-works) DB or Kafka failure. */
    static boolean isTransient(Throwable exception) {
        return Stream.iterate(exception, Objects::nonNull, Throwable::getCause)
                .limit(MAX_CAUSE_DEPTH)
                .anyMatch(cause -> TRANSIENT_FAILURES.stream().anyMatch(type -> type.isInstance(cause)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void useBetValueSerializer(DefaultKafkaProducerFactory factory) {
        factory.setValueSerializerSupplier(KafkaConfig::betValueSerializer);
    }

    private static long longConfig(Map<String, Object> config, String key, long defaultValue) {
        return Long.parseLong(String.valueOf(config.getOrDefault(key, defaultValue)));
    }

    private static BackOff boundedBackOff(JackpotProperties.Retry retry) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    private static BackOff unboundedBackOff(JackpotProperties.Retry retry) {
        ExponentialBackOff backOff = new ExponentialBackOff(retry.initialInterval().toMillis(), retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }
}
