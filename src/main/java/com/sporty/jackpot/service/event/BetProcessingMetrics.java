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
     * Records a committed processing result.
     *
     * @param event the committed result
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProcessed(BetProcessedEvent event) {
        registry.counter(PROCESSED_COUNTER, "status", event.status().name()).increment();
        latency.record(Duration.between(event.placedAt(), event.processedAt()));
        if (event.status() == ProcessingStatus.NO_MATCHING_JACKPOT) {
            log.warn("Bet {} references unknown jackpot {}: stored as NO_MATCHING_JACKPOT (no contribution, "
                    + "no evaluation)", event.betId(), event.jackpotId());
            return;
        }
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
}
