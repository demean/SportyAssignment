package com.sporty.jackpot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Whether a contributing bet won the jackpot, and the reward.
 *
 * @param betId               bet id
 * @param userId              user id
 * @param jackpotId           jackpot id
 * @param outcome             {@code WON} or {@code LOST}
 * @param won                 {@code true} when the bet won
 * @param rewardAmount        reward paid (0.00 when lost)
 * @param winChancePercentage win chance used for the draw, in percent
 * @param evaluatedAt         when the bet was evaluated
 */
@Schema(description = "The (single) reward evaluation of a contributing bet")
public record BetEvaluationResponse(
        @Schema(example = "bet-1001") String betId,
        @Schema(example = "user-42") String userId,
        @Schema(example = "jackpot-lucky") String jackpotId,
        @Schema(example = "WON", allowableValues = {"WON", "LOST"}) String outcome,
        @Schema(example = "true") boolean won,
        @Schema(example = "150.00") BigDecimal rewardAmount,
        @Schema(example = "100.0000") BigDecimal winChancePercentage,
        Instant evaluatedAt) {
}
