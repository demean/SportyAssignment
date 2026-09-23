package com.sporty.jackpot.service.event;

import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessingStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published inside the processing transaction; observed after commit only (metrics and business logs never report
 * work that was rolled back).
 *
 * @param status             processing status ({@code PROCESSED} or {@code NO_MATCHING_JACKPOT})
 * @param betId              bet id
 * @param jackpotId          referenced jackpot id
 * @param outcome            evaluation outcome; {@code null} unless {@code PROCESSED}
 * @param contributionAmount contribution; {@code null} unless {@code PROCESSED}
 * @param rewardAmount       reward (0.00 when lost); {@code null} unless {@code PROCESSED}
 * @param placedAt           when the API accepted the bet
 * @param processedAt        when the bet was processed
 */
public record BetProcessedEvent(ProcessingStatus status, String betId, String jackpotId, EvaluationOutcome outcome,
                                BigDecimal contributionAmount, BigDecimal rewardAmount, Instant placedAt,
                                Instant processedAt) {
}
