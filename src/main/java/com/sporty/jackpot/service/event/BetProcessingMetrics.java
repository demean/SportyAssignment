package com.sporty.jackpot.service.event;

import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessingStatus;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Business metrics and INFO/WARN processing logs, emitted only after the processing transaction committed.
 */
@Component
public class BetProcessingMetrics {

    static final String PROCESSED_COUNTER = "jackpot.bets.processed";
    static final String EVALUATIONS_COUNTER = "jackpot.evaluations";
    static final String REWARDS_SUMMARY = "jackpot.rewards.amount";
    static final String LATENCY_TIMER = "jackpot.bets.processing.latency";

    /**
     * Longest span recorded as processing latency (the dead-letter retention, so replays within it are covered).
     * {@code placedAt} comes from the Kafka payload, which producers other than the API (or a hand-edited replay) may
     * set to anything: a longer or negative span is not a processing latency, and a span beyond about 292 years does
     * not even fit the timer's nanoseconds ({@code ArithmeticException}).
     */
    static final Duration MAX_RECORDED_LATENCY = Duration.ofDays(30);

    private static final Logger log = LoggerFactory.getLogger(BetProcessingMetrics.class);

    private final MeterRegistry registry;
    private final DistributionSummary rewards;
    private final Timer latency;

    public BetProcessingMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.rewards = DistributionSummary.builder(REWARDS_SUMMARY)
                .description("Jackpot rewards paid out")
                .register(registry);
        this.latency = Timer.builder(LATENCY_TIMER)
                .description("Time from bet acceptance by the API to its processing")
                .register(registry);
    }

    /**
     * Records a committed processing result. The payout meters and the business log line come first, the latency
     * last: nothing about the result can be lost to an implausible {@code placedAt}.
     *
     * @param event the committed result
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProcessed(BetProcessedEvent event) {
        registry.counter(PROCESSED_COUNTER, "status", event.status().name()).increment();
        if (event.status() == ProcessingStatus.NO_MATCHING_JACKPOT) {
            log.warn("Bet {} references unknown jackpot {}: stored as NO_MATCHING_JACKPOT (no contribution, "
                    + "no evaluation)", event.betId(), event.jackpotId());
        } else {
            recordEvaluation(event);
        }
        recordLatency(Duration.between(event.placedAt(), event.processedAt()));
    }

    private void recordEvaluation(BetProcessedEvent event) {
        registry.counter(EVALUATIONS_COUNTER, "outcome", event.outcome().name()).increment();
        if (event.outcome() == EvaluationOutcome.WON) {
            rewards.record(event.rewardAmount().doubleValue());
            log.info("Bet {} WON {} on jackpot {} (contributed {}); pool reset", event.betId(), event.rewardAmount(),
                    event.jackpotId(), event.contributionAmount());
        } else {
            log.info("Bet {} processed on jackpot {}: contributed {}, LOST", event.betId(), event.jackpotId(),
                    event.contributionAmount());
        }
    }

    /** Records only plausible processing times: within [0, {@link #MAX_RECORDED_LATENCY}]. */
    private void recordLatency(Duration sinceAccepted) {
        if (!sinceAccepted.isNegative() && sinceAccepted.compareTo(MAX_RECORDED_LATENCY) <= 0) {
            latency.record(sinceAccepted);
        }
    }
}
