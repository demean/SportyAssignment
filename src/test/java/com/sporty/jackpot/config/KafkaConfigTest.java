package com.sporty.jackpot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sporty.jackpot.exception.InvalidBetException;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import com.sporty.jackpot.messaging.BetPlacedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

@DisplayName("KafkaConfig")
class KafkaConfigTest {

    private static final String BETS_TOPIC = "bets";
    private static final String DEAD_LETTER_TOPIC = "bets.DLT";
    private static final String DEAD_LETTERED = "jackpot.bets.dead-lettered";
    private static final int MAX_RETRIES = 2;
    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:30.123456Z");
    private static final BetPlacedEvent EVENT =
            new BetPlacedEvent("bet-1", "user-1", "jackpot-1", new BigDecimal("10.50"), PLACED_AT);

    private final KafkaConfig config = new KafkaConfig();

    /**
     * Distinct values for every setting, so each assertion proves the value comes from the properties.
     * Retry intervals are 1-2 ms to keep the error-handler tests fast.
     */
    private static JackpotProperties properties(Duration publishTimeout) {
        return new JackpotProperties(new JackpotProperties.Kafka(BETS_TOPIC, DEAD_LETTER_TOPIC, 12, 4, (short) 3, 2,
                Duration.ofDays(30), publishTimeout,
                new JackpotProperties.Consumer(new JackpotProperties.Retry(MAX_RETRIES, Duration.ofMillis(1), 2.0,
                        Duration.ofMillis(2)))));
    }

    private static JackpotProperties properties() {
        return properties(Duration.ofSeconds(12));
    }

    private static ConsumerRecord<String, BetPlacedEvent> betRecord() {
        return new ConsumerRecord<>(BETS_TOPIC, 7, 17L, "jackpot-1", EVENT);
    }

    /** The container wraps listener exceptions like this before handing them to the error handler. */
    private static Exception listenerFailure(Exception cause) {
        return new ListenerExecutionFailedException("Listener method threw an exception", cause);
    }

    /** Runs a task on the executor and returns the thread it ran on. */
    private static Thread threadOf(AsyncTaskExecutor executor) throws Exception {
        return executor.submit((Callable<Thread>) Thread::currentThread).get(10, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("topics")
    class Topics {

        @Test
        @DisplayName("bets topic: configured name, partitions, replication factor and min.insync.replicas")
        void betsTopic() {
            NewTopic topic = config.betsTopic(properties());

            assertThat(topic.name()).isEqualTo(BETS_TOPIC);
            assertThat(topic.numPartitions()).isEqualTo(12);
            assertThat(topic.replicationFactor()).isEqualTo((short) 3);
            assertThat(topic.configs()).isEqualTo(Map.of("min.insync.replicas", "2"));
        }

        @Test
        @DisplayName("dead-letter topic: own partitions, same replication, min.insync.replicas and 30 d retention")
        void deadLetterTopic() {
            NewTopic topic = config.deadLetterTopic(properties());

            assertThat(topic.name()).isEqualTo(DEAD_LETTER_TOPIC);
            assertThat(topic.numPartitions()).isEqualTo(4);
            assertThat(topic.replicationFactor()).isEqualTo((short) 3);
            assertThat(topic.configs()).isEqualTo(Map.of(
                    "min.insync.replicas", "2",
                    "retention.ms", String.valueOf(Duration.ofDays(30).toMillis())));
            assertThat(topic.configs().get("retention.ms")).isEqualTo("2592000000");
        }
    }

    @Nested
    @DisplayName("producer factory customizer")
    class ProducerCustomizer {

        private static DefaultKafkaProducerFactory<Object, Object> producerFactory(Map<String, Object> extra) {
            Map<String, Object> configs = new HashMap<>(extra);
            configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            return new DefaultKafkaProducerFactory<>(configs);
        }

        @Test
        @DisplayName("keeps Boot's factory and installs the by-type value serializer when the timeouts are consistent")
        void installsBetValueSerializer() {
            DefaultKafkaProducerFactory<Object, Object> factory = producerFactory(Map.of(
                    ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000", ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "8000"));

            config.betProducerFactoryCustomizer(properties(Duration.ofSeconds(12))).customize(factory);

            Serializer<Object> serializer = factory.getValueSerializerSupplier().get();
            assertThat(serializer).isInstanceOf(DelegatingByTypeSerializer.class);
            assertThat(new String(serializer.serialize(BETS_TOPIC, EVENT), StandardCharsets.UTF_8))
                    .contains("\"betId\":\"bet-1\"");
            assertThat(factory.getConfigurationProperties())
                    .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000")
                    .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "8000");
        }

        @Test
        @DisplayName("fails fast on a publish timeout shorter than max.block.ms + delivery.timeout.ms + 1s")
        void failsFastOnInconsistentPublishTimeout() {
            DefaultKafkaProducerFactory<Object, Object> factory = producerFactory(Map.of(
                    ProducerConfig.MAX_BLOCK_MS_CONFIG, "3000", ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "8000"));

            assertThatThrownBy(() -> config.betProducerFactoryCustomizer(properties(Duration.ofMillis(11_999)))
                    .customize(factory))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("jackpot.kafka.publish-timeout (PT11.999S) must be >= max.block.ms + "
                            + "delivery.timeout.ms + 1s (PT12S)");
            assertThat(factory.getValueSerializerSupplier().get()).as("serializer untouched").isNull();
        }

        static Stream<Arguments> publishTimeouts() {
            return Stream.of(
                    Arguments.of("exactly the minimum (production values)", Duration.ofSeconds(12),
                            Map.of("max.block.ms", "3000", "delivery.timeout.ms", "8000"), true),
                    Arguments.of("1 ms below the minimum", Duration.ofMillis(11_999),
                            Map.of("max.block.ms", "3000", "delivery.timeout.ms", "8000"), false),
                    Arguments.of("numeric config values", Duration.ofSeconds(12),
                            Map.of("max.block.ms", 3000, "delivery.timeout.ms", 8000L), true),
                    Arguments.of("Kafka defaults (60 s + 120 s) when not configured", Duration.ofSeconds(181),
                            Map.of(), true),
                    Arguments.of("below the Kafka defaults", Duration.ofMillis(180_999), Map.of(), false),
                    Arguments.of("default delivery.timeout.ms only", Duration.ofSeconds(124),
                            Map.of("max.block.ms", "3000"), true),
                    Arguments.of("default max.block.ms only", Duration.ofMillis(68_999),
                            Map.of("delivery.timeout.ms", "8000"), false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("publishTimeouts")
        @DisplayName("publish timeout validation")
        void validatePublishTimeout(String description, Duration publishTimeout, Map<String, Object> producerConfig,
                                    boolean valid) {
            if (valid) {
                assertThatCode(() -> KafkaConfig.validatePublishTimeout(publishTimeout, producerConfig))
                        .doesNotThrowAnyException();
            }
            else {
                assertThatThrownBy(() -> KafkaConfig.validatePublishTimeout(publishTimeout, producerConfig))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageStartingWith("jackpot.kafka.publish-timeout (" + publishTimeout + ")");
            }
        }
    }

    @Nested
    @DisplayName("value serializer")
    class ValueSerializer {

        private final Serializer<Object> serializer = KafkaConfig.betValueSerializer();

        @Test
        @DisplayName("writes BetPlacedEvent as JSON without type headers, even after producer configuration")
        void betPlacedEventAsJsonWithoutTypeHeaders() {
            serializer.configure(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"), false);
            RecordHeaders headers = new RecordHeaders();

            byte[] json = serializer.serialize(BETS_TOPIC, headers, EVENT);

            assertThat(headers.toArray()).as("no __TypeId__ headers").isEmpty();
            assertThat(new String(json, StandardCharsets.UTF_8))
                    .contains("\"betAmount\":10.50")
                    .contains("\"placedAt\":\"2026-09-23T10:15:30.123456Z\"");
            try (JacksonJsonDeserializer<BetPlacedEvent> deserializer =
                         new JacksonJsonDeserializer<>(BetPlacedEvent.class, false)) {
                BetPlacedEvent roundTripped = deserializer.deserialize(BETS_TOPIC, json);
                assertThat(roundTripped).isEqualTo(EVENT);
                assertThat(roundTripped.betAmount().scale()).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("passes raw byte[] (dead-lettered poison pills) through unchanged")
        void rawBytesPassThrough() {
            byte[] poisonPill = "{not json".getBytes(StandardCharsets.UTF_8);
            RecordHeaders headers = new RecordHeaders();

            assertThat(serializer.serialize(DEAD_LETTER_TOPIC, headers, poisonPill)).isEqualTo(poisonPill);
            assertThat(headers.toArray()).isEmpty();
        }

        @Test
        @DisplayName("serializes a null value (tombstone) as null")
        void nullStaysNull() {
            assertThat(serializer.serialize(BETS_TOPIC, new RecordHeaders(), null)).isNull();
        }

        @Test
        @DisplayName("rejects any other payload type")
        void rejectsUnknownTypes() {
            assertThatThrownBy(() -> serializer.serialize(BETS_TOPIC, new RecordHeaders(), "plain string"))
                    .isInstanceOf(SerializationException.class)
                    .hasMessageContaining("No matching delegate for type: java.lang.String");
        }

        @Test
        @DisplayName("the JSON delegate is the same Jackson 3 serializer the consumer side can read")
        void usesJacksonJsonSerializer() {
            try (JacksonJsonSerializer<BetPlacedEvent> reference = new JacksonJsonSerializer<BetPlacedEvent>()
                    .noTypeInfo()) {
                assertThat(serializer.serialize(BETS_TOPIC, EVENT)).isEqualTo(reference.serialize(BETS_TOPIC, EVENT));
            }
        }
    }

    @Nested
    @DisplayName("listener container customizer")
    class ListenerContainerCustomizer {

        @Test
        @DisplayName("platform-thread executor, 15 s shutdown timeout and deserialization checks for null key/value")
        void customizesContainer() throws Exception {
            @SuppressWarnings("unchecked")
            ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
            ConcurrentMessageListenerContainer<Object, Object> container =
                    new ConcurrentMessageListenerContainer<>(consumerFactory, new ContainerProperties(BETS_TOPIC));

            config.listenerContainerCustomizer().configure(container);

            ContainerProperties containerProperties = container.getContainerProperties();
            assertThat(containerProperties.getShutdownTimeout()).isEqualTo(15_000L);
            assertThat(containerProperties.isCheckDeserExWhenKeyNull()).isTrue();
            assertThat(containerProperties.isCheckDeserExWhenValueNull()).isTrue();
            AsyncTaskExecutor executor = containerProperties.getListenerTaskExecutor();
            assertThat(executor).isInstanceOfSatisfying(SimpleAsyncTaskExecutor.class,
                    simple -> assertThat(simple.getThreadNamePrefix()).isEqualTo("jackpot-kafka-"));

            Thread pollThread = threadOf(executor);
            assertThat(pollThread.isVirtual()).as("Kafka poll loops must not run on virtual threads").isFalse();
            assertThat(pollThread.getName()).startsWith("jackpot-kafka-");
        }
    }

    @Nested
    @DisplayName("error handler")
    class ErrorHandler {

        @SuppressWarnings("unchecked")
        private final KafkaOperations<Object, Object> kafkaOperations = mock(KafkaOperations.class);
        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final Consumer<?, ?> consumer = mock(Consumer.class);
        private final MessageListenerContainer container = mock(MessageListenerContainer.class);
        private final ConsumerRecord<String, BetPlacedEvent> failedRecord = betRecord();
        private CommonErrorHandler errorHandler;

        @BeforeEach
        void setUp() {
            when(kafkaOperations.send(anyProducerRecord())).thenAnswer(invocation -> {
                ProducerRecord<Object, Object> sent = invocation.getArgument(0);
                RecordMetadata metadata = new RecordMetadata(new TopicPartition(sent.topic(), 0), 0L, 0, 0L, 0, 0);
                return CompletableFuture.completedFuture(new SendResult<>(sent, metadata));
            });
            errorHandler = config.kafkaErrorHandler(kafkaOperations, properties(), meterRegistry);
        }

        /** Matches any non-null producer record (typed, so the {@code send} overload resolves without warnings). */
        @SuppressWarnings("unchecked")
        private static ProducerRecord<Object, Object> anyProducerRecord() {
            return any(ProducerRecord.class);
        }

        /** The container's path for a failed record: handleRemaining with the failed record first. */
        private void deliver(Exception failure) {
            errorHandler.handleRemaining(failure, List.of(failedRecord), consumer, container);
        }

        private void assertStillInRetry(Exception failure) {
            assertThatThrownBy(() -> deliver(failure))
                    .hasMessage("Record in retry and not yet recovered")
                    .hasCause(failure);
        }

        private ProducerRecord<Object, Object> deadLetteredRecord() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<ProducerRecord<Object, Object>> captor =
                    ArgumentCaptor.forClass((Class) ProducerRecord.class);
            verify(kafkaOperations).send(captor.capture());
            return captor.getValue();
        }

        private void assertDeadLettered(String expectedExceptionTag) {
            ProducerRecord<Object, Object> dlt = deadLetteredRecord();
            assertThat(dlt.topic()).isEqualTo(DEAD_LETTER_TOPIC);
            assertThat(dlt.partition()).as("partition chosen by the producer").isNull();
            assertThat(dlt.key()).isEqualTo("jackpot-1");
            assertThat(dlt.value()).isEqualTo(EVENT);
            assertThat(new String(dlt.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                    StandardCharsets.UTF_8)).isEqualTo(BETS_TOPIC);
            assertThat(meterRegistry.get(DEAD_LETTERED).tag("exception", expectedExceptionTag).counter().count())
                    .isEqualTo(1.0);
        }

        private void assertNotDeadLettered() {
            verify(kafkaOperations, never()).send(anyProducerRecord());
            assertThat(meterRegistry.find(DEAD_LETTERED).counter()).isNull();
        }

        static Stream<Arguments> deterministicFailures() {
            return Stream.of(
                    Arguments.of(new InvalidBetException("amount must be positive but was -1.00"),
                            "InvalidBetException"),
                    Arguments.of(new JackpotConfigurationException("Invalid reward policy JSON: ..."),
                            "JackpotConfigurationException"),
                    Arguments.of(new JpaSystemException(new PersistenceException("Error attempting to apply "
                                    + "AttributeConverter", new JackpotConfigurationException("Invalid policy JSON"))),
                            "JackpotConfigurationException"),
                    Arguments.of(new DataIntegrityViolationException("check constraint ck_bet_amount",
                                    new SQLIntegrityConstraintViolationException("23513")),
                            "SQLIntegrityConstraintViolationException"),
                    Arguments.of(new DuplicateKeyException("duplicate key uk_jackpot_reward_cycle"),
                            "DuplicateKeyException"),
                    Arguments.of(new DeserializationException("failed to deserialize",
                                    "{oops".getBytes(StandardCharsets.UTF_8), false,
                                    new IllegalArgumentException("Unexpected character")),
                            "IllegalArgumentException"));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("deterministicFailures")
        @DisplayName("deterministic failures are dead-lettered on the first delivery, without retry or seek")
        void deterministicFailuresAreDeadLetteredImmediately(Exception cause, String exceptionTag) {
            assertThatCode(() -> deliver(listenerFailure(cause))).doesNotThrowAnyException();

            verify(consumer, never()).seek(any(TopicPartition.class), anyLong());
            assertDeadLettered(exceptionTag);
        }

        static Stream<Arguments> unknownFailures() {
            return Stream.of(
                    Arguments.of(new IllegalStateException("unexpected state"), "IllegalStateException"),
                    Arguments.of(new InvalidDataAccessApiUsageException("bad query"),
                            "InvalidDataAccessApiUsageException"),
                    Arguments.of(new JpaSystemException(new PersistenceException("flush failed")),
                            "PersistenceException"));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("unknownFailures")
        @DisplayName("unknown failures are retried max-retries times (re-seeking the record), then dead-lettered")
        void unknownFailuresAreRetriedBoundedThenDeadLettered(Exception cause, String exceptionTag) {
            Exception failure = listenerFailure(cause);

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                assertStillInRetry(failure);
            }
            verify(consumer, times(MAX_RETRIES)).seek(new TopicPartition(BETS_TOPIC, 7), 17L);
            assertNotDeadLettered();

            assertThatCode(() -> deliver(failure)).doesNotThrowAnyException();

            assertDeadLettered(exceptionTag);
        }

        static Stream<Exception> transientFailures() {
            return Stream.of(
                    new CannotAcquireLockException("lock timeout on jackpot row"),
                    new QueryTimeoutException("statement timeout"),
                    new RecoverableDataAccessException("connection reset, retry"),
                    new CannotCreateTransactionException("Could not open JPA EntityManager"),
                    new DataAccessResourceFailureException("database down"),
                    new JpaSystemException(new PersistenceException("wrapped",
                            new SQLTransientConnectionException("connection is not available"))),
                    new KafkaException("send failed", new NotEnoughReplicasException("not enough replicas")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("transientFailures")
        @DisplayName("transient DB/Kafka failures are retried far beyond max-retries and never dead-lettered")
        void transientFailuresAreRetriedIndefinitely(Exception cause) {
            Exception failure = listenerFailure(cause);
            int attempts = 5 * MAX_RETRIES + 3;

            for (int attempt = 1; attempt <= attempts; attempt++) {
                assertStillInRetry(failure);
            }

            verify(consumer, times(attempts)).seek(new TopicPartition(BETS_TOPIC, 7), 17L);
            assertNotDeadLettered();
        }

        @Test
        @DisplayName("a failed dead-letter publication re-seeks the record and is not counted as dead-lettered")
        void failedDeadLetterPublicationKeepsTheRecord() {
            when(kafkaOperations.send(anyProducerRecord()))
                    .thenReturn(CompletableFuture.failedFuture(new NotEnoughReplicasException("DLT unavailable")));
            Exception failure = listenerFailure(new InvalidBetException("bad bet"));

            assertThatThrownBy(() -> deliver(failure)).hasMessage("Record in retry and not yet recovered");
            assertThatThrownBy(() -> deliver(failure)).hasMessage("Record in retry and not yet recovered");

            verify(consumer, times(2)).seek(new TopicPartition(BETS_TOPIC, 7), 17L);
            verify(kafkaOperations, times(2)).send(anyProducerRecord());
            assertThat(meterRegistry.find(DEAD_LETTERED).counter()).isNull();
        }
    }

    @Nested
    @DisplayName("Spring Boot wiring (application context without a broker)")
    class BootWiring {

        private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
                .withUserConfiguration(KafkaConfig.class)
                .withBean(JackpotProperties.class, KafkaConfigTest::properties)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "spring.threads.virtual.enabled=true",
                        "spring.kafka.admin.auto-create=false",
                        "spring.kafka.producer.properties.max.block.ms=3000",
                        "spring.kafka.producer.properties.delivery.timeout.ms=8000");

        @Test
        @DisplayName("Boot's own producer and listener container factories pick up every KafkaConfig bean")
        void bootFactoriesPickUpTheBeans() throws Exception {
            contextRunner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeansOfType(NewTopic.class).values())
                        .extracting(NewTopic::name)
                        .containsExactlyInAnyOrder(BETS_TOPIC, DEAD_LETTER_TOPIC);

                DefaultKafkaProducerFactory<?, ?> producerFactory = context.getBean(DefaultKafkaProducerFactory.class);
                assertThat(producerFactory.getValueSerializerSupplier().get())
                        .isInstanceOf(DelegatingByTypeSerializer.class);

                ConcurrentKafkaListenerContainerFactory<?, ?> listenerFactory =
                        context.getBean(ConcurrentKafkaListenerContainerFactory.class);
                ConcurrentMessageListenerContainer<?, ?> container = listenerFactory.createContainer(BETS_TOPIC);
                assertThat(container.getCommonErrorHandler()).isSameAs(context.getBean(CommonErrorHandler.class));
                ContainerProperties containerProperties = container.getContainerProperties();
                assertThat(containerProperties.getShutdownTimeout()).isEqualTo(15_000L);
                assertThat(containerProperties.isCheckDeserExWhenKeyNull()).isTrue();
                assertThat(containerProperties.isCheckDeserExWhenValueNull()).isTrue();

                AsyncTaskExecutor bootDefault = listenerFactory.getContainerProperties().getListenerTaskExecutor();
                assertThat(threadOf(bootDefault).isVirtual())
                        .as("with spring.threads.virtual.enabled Boot's default listener executor is virtual")
                        .isTrue();
                assertThat(threadOf(containerProperties.getListenerTaskExecutor()).isVirtual())
                        .as("the customizer puts every created container back on platform threads")
                        .isFalse();
            });
        }

        @Test
        @DisplayName("startup fails fast when the producer timeouts exceed the publish timeout")
        void startupFailsOnInconsistentPublishTimeout() {
            contextRunner
                    .withPropertyValues("spring.kafka.producer.properties.delivery.timeout.ms=120000")
                    .run(context -> assertThat(context)
                            .getFailure()
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageStartingWith("jackpot.kafka.publish-timeout (PT12S)"));
        }
    }

    @Nested
    @DisplayName("building blocks")
    class BuildingBlocks {

        @Test
        @DisplayName("dead-letter destination: the configured topic, partition left to the producer (-1)")
        void deadLetterDestination() {
            BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destination =
                    KafkaConfig.deadLetterDestination(DEAD_LETTER_TOPIC);

            assertThat(destination.apply(betRecord(), new IllegalStateException("boom")))
                    .isEqualTo(new TopicPartition(DEAD_LETTER_TOPIC, -1));
            assertThat(destination.apply(new ConsumerRecord<>(BETS_TOPIC, 11, 0L, "k", "v"), new RuntimeException()))
                    .as("independent of the source partition (the DLT has fewer partitions)")
                    .isEqualTo(new TopicPartition(DEAD_LETTER_TOPIC, -1));
        }

        @Test
        @DisplayName("counting recoverer: delegates the same record and exception, then counts by most specific cause")
        void countingRecovererDelegatesThenCounts() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
            ConsumerRecord<String, BetPlacedEvent> failed = betRecord();
            Exception failure = listenerFailure(new DataIntegrityViolationException("fk violated",
                    new SQLIntegrityConstraintViolationException("23506")));

            ConsumerRecordRecoverer recoverer = KafkaConfig.countingRecoverer(delegate, meterRegistry);
            recoverer.accept(failed, failure);
            recoverer.accept(failed, listenerFailure(new InvalidBetException("bad")));
            recoverer.accept(failed, listenerFailure(new InvalidBetException("worse")));

            verify(delegate).accept(failed, failure);
            verify(delegate, times(3)).accept(any(), any());
            assertThat(counter(meterRegistry, "SQLIntegrityConstraintViolationException").count()).isEqualTo(1.0);
            assertThat(counter(meterRegistry, "InvalidBetException").count()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("counting recoverer: a failing delegate propagates its exception and nothing is counted")
        void countingRecovererPropagatesDelegateFailure() {
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            ConsumerRecordRecoverer delegate = mock(ConsumerRecordRecoverer.class);
            KafkaException publishFailure = new KafkaException("Dead-letter publication failed");
            doThrow(publishFailure).when(delegate).accept(any(), any());
            ConsumerRecordRecoverer recoverer = KafkaConfig.countingRecoverer(delegate, meterRegistry);

            assertThatThrownBy(() -> recoverer.accept(betRecord(), new IllegalStateException("boom")))
                    .isSameAs(publishFailure);
            assertThat(meterRegistry.find(DEAD_LETTERED).counter()).isNull();
        }

        private static Counter counter(SimpleMeterRegistry registry, String exception) {
            return registry.get(DEAD_LETTERED).tag("exception", exception).counter();
        }

        @Test
        @DisplayName("back-off function: the unbounded back-off for transient failures, the bounded default otherwise")
        void backOffFunction() {
            BackOff transientBackOff = new FixedBackOff(1L, FixedBackOff.UNLIMITED_ATTEMPTS);
            BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> function =
                    KafkaConfig.backOffFunction(transientBackOff);

            assertThat(function.apply(betRecord(), listenerFailure(new CannotAcquireLockException("lock"))))
                    .isSameAs(transientBackOff);
            assertThat(function.apply(betRecord(), listenerFailure(new IllegalStateException("bug")))).isNull();
        }

        static Stream<Throwable> transientChains() {
            return Stream.of(
                    new CannotAcquireLockException("lock timeout"),
                    new QueryTimeoutException("timeout"),
                    new RecoverableDataAccessException("recoverable"),
                    new CannotCreateTransactionException("no connection"),
                    new DataAccessResourceFailureException("db down"),
                    new SQLTransientException("transient"),
                    new SQLTransientConnectionException("connection reset"),
                    new NotEnoughReplicasException("retriable Kafka error"),
                    new org.apache.kafka.common.errors.TimeoutException("retriable Kafka timeout"),
                    new RuntimeException("outer", new IllegalStateException("middle",
                            new SQLTransientConnectionException("deeply nested"))),
                    cyclicChain(new CannotAcquireLockException("transient inside a cause cycle")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("transientChains")
        @DisplayName("isTransient: true when the cause chain contains a transient DB or Kafka failure")
        void transientCauseChains(Throwable failure) {
            assertThat(KafkaConfig.isTransient(failure)).isTrue();
        }

        static Stream<Throwable> nonTransientChains() {
            return Stream.of(
                    new IllegalStateException("bug"),
                    new InvalidBetException("bad bet"),
                    new DataIntegrityViolationException("constraint"),
                    new InvalidDataAccessApiUsageException("api misuse"),
                    new SQLNonTransientException("syntax error"),
                    new SerializationException("non-retriable Kafka error"),
                    new RuntimeException("outer", new IllegalArgumentException("inner")),
                    cyclicChain(new IllegalArgumentException("non-transient cause cycle")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("nonTransientChains")
        @DisplayName("isTransient: false otherwise, and terminates on cyclic cause chains")
        void nonTransientCauseChains(Throwable failure) {
            assertThat(KafkaConfig.isTransient(failure)).isFalse();
        }

        /** {@code IllegalStateException -> cause -> IllegalStateException(first)} loop. */
        private static Throwable cyclicChain(RuntimeException cause) {
            IllegalStateException first = new IllegalStateException("first");
            IllegalStateException second = new IllegalStateException("second", cause);
            first.initCause(new IllegalStateException("loop", second));
            cause.initCause(first);
            return first;
        }
    }
}
