package com.sporty.jackpot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read model of a bet's (single, eager) reward evaluation.
 *
 * @param betId               evaluated bet
 * @param userId              betting user
 * @param jackpotId           jackpot the bet contributed to
 * @param outcome             WON or LOST
 * @param winChancePercentage win chance used for the draw, in percent (scale 4)
 * @param rewardAmount        reward paid (0.00 when lost)
 * @param jackpotCycle        jackpot cycle in which the bet was evaluated
 * @param evaluatedAt         when the evaluation happened
 */
public record BetEvaluation(String betId, String userId, String jackpotId, EvaluationOutcome outcome,
                            BigDecimal winChancePercentage, BigDecimal rewardAmount, long jackpotCycle,
                            Instant evaluatedAt) {

    /**
     * Whether the bet won the jackpot.
     *
     * @return {@code true} for {@link EvaluationOutcome#WON}
     */
    public boolean won() {
        return outcome == EvaluationOutcome.WON;
    }
}
