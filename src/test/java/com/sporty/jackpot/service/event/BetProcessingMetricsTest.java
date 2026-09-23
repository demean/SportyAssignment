package com.sporty.jackpot.service.event;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessingStatus;
import com.sporty.jackpot.service.ServiceLogCapture;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@DisplayName("BetProcessingMetrics")
class BetProcessingMetricsTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:29.000000100Z");
    /** 1.123456689 s after {@link #PLACED_AT}: nanosecond precision must survive into the timer. */
    private static final Instant PROCESSED_AT = Instant.parse("2026-09-23T10:15:30.123456789Z");
    private static final long LATENCY_NANOS = 1_123_456_689L;

    private SimpleMeterRegistry registry;
    private BetProcessingMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BetProcessingMetrics(registry);
    }

    private static BetProcessedEvent won(String betId, String reward) {
        return new BetProcessedEvent(ProcessingStatus.PROCESSED, betId, "jackpot-1", EvaluationOutcome.WON,
                new BigDecimal("10.00"), new BigDecimal(reward), PLACED_AT, PROCESSED_AT);
    }

    private static BetProcessedEvent lost(String betId) {
        return new BetProcessedEvent(ProcessingStatus.PROCESSED, betId, "jackpot-1", EvaluationOutcome.LOST,
                new BigDecimal("12.50"), new BigDecimal("0.00"), PLACED_AT, PROCESSED_AT);
    }

    private static BetProcessedEvent noMatchingJackpot(String betId) {
        return new BetProcessedEvent(ProcessingStatus.NO_MATCHING_JACKPOT, betId, "jackpot-unknown", null, null, null,
                PLACED_AT, PROCESSED_AT);
    }

    private double processed(ProcessingStatus status) {
        return registry.get(BetProcessingMetrics.PROCESSED_COUNTER).tag("status", status.name()).counter().count();
    }

    private double evaluations(EvaluationOutcome outcome) {
        return registry.get(BetProcessingMetrics.EVALUATIONS_COUNTER).tag("outcome", outcome.name()).counter()
                .count();
    }

    private DistributionSummary rewards() {
        return registry.get(BetProcessingMetrics.REWARDS_SUMMARY).summary();
    }

    private Timer latency() {
        return registry.get(BetProcessingMetrics.LATENCY_TIMER).timer();
    }

    @Test
    @DisplayName("listens only AFTER_COMMIT (never reports rolled-back work)")
    void listensAfterCommit() throws NoSuchMethodException {
        TransactionalEventListener listener = BetProcessingMetrics.class
                .getMethod("onProcessed", BetProcessedEvent.class).getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("reward summary and latency timer are registered up front, empty")
    void metersRegisteredOnConstruction() {
        assertThat(rewards().count()).isZero();
        assertThat(rewards().getId().getDescription()).isEqualTo("Jackpot rewards paid out");
        assertThat(latency().count()).isZero();
        assertThat(latency().getId().getDescription())
                .isEqualTo("Time from bet acceptance by the API to its processing");
    }

    @Test
    @DisplayName("WON: processed{PROCESSED}, evaluations{WON}, reward amount, latency, INFO log")
    void wonBet() {
        try (ServiceLogCapture logs = ServiceLogCapture.of(BetProcessingMetrics.class)) {
            metrics.onProcessed(won("bet-1", "1010.00"));

            assertThat(logs.messages(Level.INFO))
                    .containsExactly("Bet bet-1 WON 1010.00 on jackpot jackpot-1 (contributed 10.00); pool reset");
            assertThat(logs.messages(Level.WARN)).isEmpty();
        }

        assertThat(processed(ProcessingStatus.PROCESSED)).isEqualTo(1.0);
        assertThat(evaluations(EvaluationOutcome.WON)).isEqualTo(1.0);
        assertThat(registry.find(BetProcessingMetrics.EVALUATIONS_COUNTER).tag("outcome", "LOST").counter()).isNull();
        assertThat(rewards().count()).isOne();
        assertThat(rewards().totalAmount()).isEqualTo(1010.0);
        assertThat(latency().count()).isOne();
        assertThat(latency().totalTime(TimeUnit.NANOSECONDS)).isEqualTo(LATENCY_NANOS);
    }

    @Test
    @DisplayName("LOST: processed{PROCESSED}, evaluations{LOST}, latency, no reward, INFO log")
    void lostBet() {
        try (ServiceLogCapture logs = ServiceLogCapture.of(BetProcessingMetrics.class)) {
            metrics.onProcessed(lost("bet-2"));

            assertThat(logs.messages(Level.INFO))
                    .containsExactly("Bet bet-2 processed on jackpot jackpot-1: contributed 12.50, LOST");
            assertThat(logs.messages(Level.WARN)).isEmpty();
        }

        assertThat(processed(ProcessingStatus.PROCESSED)).isEqualTo(1.0);
        assertThat(evaluations(EvaluationOutcome.LOST)).isEqualTo(1.0);
        assertThat(registry.find(BetProcessingMetrics.EVALUATIONS_COUNTER).tag("outcome", "WON").counter()).isNull();
        assertThat(rewards().count()).isZero();
        assertThat(latency().count()).isOne();
        assertThat(latency().totalTime(TimeUnit.NANOSECONDS)).isEqualTo(LATENCY_NANOS);
    }

    @Test
    @DisplayName("NO_MATCHING_JACKPOT: processed{NO_MATCHING_JACKPOT}, latency, no evaluation/reward, WARN log")
    void noMatchingJackpot() {
        try (ServiceLogCapture logs = ServiceLogCapture.of(BetProcessingMetrics.class)) {
            metrics.onProcessed(noMatchingJackpot("bet-3"));

            assertThat(logs.messages(Level.WARN)).containsExactly("Bet bet-3 references unknown jackpot "
                    + "jackpot-unknown: stored as NO_MATCHING_JACKPOT (no contribution, no evaluation)");
            assertThat(logs.messages(Level.INFO)).isEmpty();
        }

        assertThat(processed(ProcessingStatus.NO_MATCHING_JACKPOT)).isEqualTo(1.0);
        assertThat(registry.find(BetProcessingMetrics.PROCESSED_COUNTER).tag("status", "PROCESSED").counter())
                .isNull();
        assertThat(registry.find(BetProcessingMetrics.EVALUATIONS_COUNTER).counters()).isEmpty();
        assertThat(rewards().count()).isZero();
        assertThat(latency().count()).isOne();
        assertThat(latency().totalTime(TimeUnit.NANOSECONDS)).isEqualTo(LATENCY_NANOS);
    }

    @Test
    @DisplayName("clock skew between API and consumer instance (processedAt < placedAt) never fails the listener")
    void negativeLatencyIsIgnoredByTheTimer() {
        BetProcessedEvent skewed = new BetProcessedEvent(ProcessingStatus.PROCESSED, "bet-5", "jackpot-1",
                EvaluationOutcome.LOST, new BigDecimal("1.00"), new BigDecimal("0.00"), PROCESSED_AT, PLACED_AT);

        metrics.onProcessed(skewed);

        assertThat(processed(ProcessingStatus.PROCESSED)).isEqualTo(1.0);
        assertThat(evaluations(EvaluationOutcome.LOST)).isEqualTo(1.0);
        assertThat(latency().count()).as("negative durations are dropped by Micrometer").isZero();
    }

    @Test
    @DisplayName("meters accumulate per status and per outcome")
    void accumulatesPerTag() {
        metrics.onProcessed(won("bet-1", "1010.00"));
        metrics.onProcessed(won("bet-2", "250.50"));
        metrics.onProcessed(lost("bet-3"));
        metrics.onProcessed(noMatchingJackpot("bet-4"));

        assertThat(processed(ProcessingStatus.PROCESSED)).isEqualTo(3.0);
        assertThat(processed(ProcessingStatus.NO_MATCHING_JACKPOT)).isEqualTo(1.0);
        assertThat(evaluations(EvaluationOutcome.WON)).isEqualTo(2.0);
        assertThat(evaluations(EvaluationOutcome.LOST)).isEqualTo(1.0);
        assertThat(rewards().count()).isEqualTo(2);
        assertThat(rewards().totalAmount()).isEqualTo(1260.5);
        assertThat(latency().count()).isEqualTo(4);
        assertThat(latency().totalTime(TimeUnit.NANOSECONDS)).isEqualTo(4.0 * LATENCY_NANOS);
    }
}
