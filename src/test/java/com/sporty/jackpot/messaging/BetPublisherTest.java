package com.sporty.jackpot.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.sporty.jackpot.config.JackpotProperties;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.exception.BetPublishingException;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.service.BetEventPublisher;
import com.sporty.jackpot.support.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("BetPublisher")
class BetPublisherTest {

    private static final String TOPIC = "bets-under-test";
    private static final Duration PUBLISH_TIMEOUT = Duration.ofMillis(1_500);
    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:30.123456789Z");
    private static final Bet BET = new Bet("bet-1", "user-1", "jackpot-1", new BigDecimal("25.50"), PLACED_AT);
    private static final BetPlacedEvent EVENT = new BetPlacedEvent("bet-1", "user-1", "jackpot-1",
            new BigDecimal("25.50"), PLACED_AT);

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private BetPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = publisherWithPublishTimeout(PUBLISH_TIMEOUT);
    }

    private BetPublisher publisherWithPublishTimeout(Duration publishTimeout) {
        JackpotProperties properties = new JackpotProperties(new JackpotProperties.Kafka(TOPIC, TOPIC + ".DLT", 12, 3,
                (short) 1, 1, Duration.ofDays(30), publishTimeout,
                new JackpotProperties.Consumer(new JackpotProperties.Retry(2, Duration.ofMillis(10), 2.0,
                        Duration.ofMillis(50)))));
        return new BetPublisher(kafkaTemplate, new BetEventMapper(), properties, registry);
    }

    @AfterEach
    void clearInterruptFlag() {
        // never leak an interrupt into the next test, even when an assertion failed before it was cleared
        Thread.interrupted();
    }

    private double published(String result) {
        return registry.get(BetPublisher.PUBLISHED_COUNTER).tag("result", result).counter().count();
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, Object>> sendReturnsFutureMock() {
        CompletableFuture<SendResult<String, Object>> future = mock(CompletableFuture.class);
        when(kafkaTemplate.send(TOPIC, "jackpot-1", EVENT)).thenReturn(future);
        return future;
    }

    private BetPublishingException publishFails() {
        return catchThrowableOfType(BetPublishingException.class, () -> publisher.publish(BET));
    }

    private void assertFailureReported(BetPublishingException exception, Throwable expectedCause) {
        assertThat(exception).isNotNull();
        assertThat(exception.getBetId()).isEqualTo("bet-1");
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BET_PUBLISH_FAILED);
        assertThat(exception.getMessage()).contains("bet-1", "outcome unknown", "retry with the same betId");
        assertThat(exception.getCause()).isSameAs(expectedCause);
        assertThat(published("failure")).isEqualTo(1.0);
        assertThat(published("success")).isZero();
    }

    @Test
    @DisplayName("is the Kafka adapter of the service's BetEventPublisher port")
    void implementsTheServicePort() {
        assertThat(publisher).isInstanceOf(BetEventPublisher.class);
    }

    @Test
    @DisplayName("success: sends the event keyed by jackpot id to the bets topic and waits for the acknowledgement")
    void publishesKeyedByJackpotId() throws Exception {
        CompletableFuture<SendResult<String, Object>> future = sendReturnsFutureMock();
        when(future.get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).thenReturn(new SendResult<>(
                new ProducerRecord<>(TOPIC, "jackpot-1", EVENT),
                new RecordMetadata(new TopicPartition(TOPIC, 7), 42L, 0, 0L, 9, 120)));

        try (LogCapture logs = LogCapture.of(BetPublisher.class)) {
            publisher.publish(BET);

            assertThat(logs.messages(Level.DEBUG)).containsExactly("Published bet bet-1 to bets-under-test-7@42");
            assertThat(logs.messages(Level.WARN)).isEmpty();
        }

        verify(kafkaTemplate).send(TOPIC, "jackpot-1", EVENT);
        verify(future).get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(published("success")).isEqualTo(1.0);
        assertThat(published("failure")).isZero();
    }

    @Test
    @DisplayName("broker failure (ExecutionException) -> BetPublishingException, failure counted, WARN logged")
    void executionExceptionIsReported() {
        KafkaProducerException brokerFailure = new KafkaProducerException(new ProducerRecord<>(TOPIC, "jackpot-1",
                EVENT), "Failed to send", new org.apache.kafka.common.errors.NotEnoughReplicasException("isr"));
        when(kafkaTemplate.send(TOPIC, "jackpot-1", EVENT))
                .thenReturn(CompletableFuture.failedFuture(brokerFailure));

        BetPublishingException exception;
        try (LogCapture logs = LogCapture.of(BetPublisher.class)) {
            exception = publishFails();

            assertThat(logs.messages(Level.WARN)).singleElement().asString()
                    .startsWith("Publishing bet bet-1 failed, outcome unknown: "
                            + "java.util.concurrent.ExecutionException");
        }

        assertThat(exception).isNotNull();
        assertThat(exception.getCause()).isInstanceOf(ExecutionException.class).hasCause(brokerFailure);
        assertFailureReported(exception, exception.getCause());
    }

    @Test
    @DisplayName("no acknowledgement within publish-timeout (TimeoutException) -> BetPublishingException")
    void timeoutIsReported() throws Exception {
        TimeoutException timeout = new TimeoutException("no ack");
        when(sendReturnsFutureMock().get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).thenThrow(timeout);

        assertFailureReported(publishFails(), timeout);
    }

    @Test
    @DisplayName("TimeoutException from a real future that is never acknowledged")
    void neverAcknowledgedFutureTimesOut() {
        publisher = publisherWithPublishTimeout(Duration.ofMillis(20));
        when(kafkaTemplate.send(TOPIC, "jackpot-1", EVENT)).thenReturn(new CompletableFuture<>());

        BetPublishingException exception = publishFails();

        assertThat(exception).isNotNull();
        assertFailureReported(exception, exception.getCause());
        assertThat(exception.getCause()).isInstanceOf(TimeoutException.class);
    }

    static Stream<Arguments> sendFailures() {
        return Stream.of(
                Arguments.of(new org.apache.kafka.common.KafkaException("producer closed")),
                Arguments.of(new SerializationException("cannot serialize")),
                Arguments.of(new org.apache.kafka.common.errors.TimeoutException("max.block.ms elapsed")),
                Arguments.of(new org.springframework.kafka.KafkaException("template failure")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sendFailures")
    @DisplayName("KafkaException thrown synchronously by send() -> BetPublishingException")
    void kafkaExceptionFromSendIsReported(RuntimeException sendFailure) {
        when(kafkaTemplate.send(TOPIC, "jackpot-1", EVENT)).thenThrow(sendFailure);

        assertFailureReported(publishFails(), sendFailure);
    }

    @Test
    @DisplayName("InterruptedException -> interrupt flag restored + BetPublishingException")
    void interruptIsRestored() throws Exception {
        InterruptedException interrupted = new InterruptedException("shutting down");
        when(sendReturnsFutureMock().get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).thenThrow(interrupted);

        BetPublishingException exception = publishFails();

        assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag restored").isTrue();
        assertThat(Thread.interrupted()).as("clears the flag for the following tests").isTrue();
        assertFailureReported(exception, interrupted);
    }
}
