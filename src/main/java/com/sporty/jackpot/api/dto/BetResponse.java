package com.sporty.jackpot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A processed bet.
 *
 * @param betId       bet id
 * @param userId      user id
 * @param jackpotId   referenced jackpot id
 * @param betAmount   stake
 * @param status      {@code CONTRIBUTED} or {@code NO_MATCHING_JACKPOT}
 * @param placedAt    when the bet was accepted
 * @param processedAt when the bet was processed
 */
@Schema(description = "A processed bet")
public record BetResponse(
        @Schema(example = "bet-1001") String betId,
        @Schema(example = "user-42") String userId,
        @Schema(example = "jackpot-lucky") String jackpotId,
        @Schema(example = "250.00") BigDecimal betAmount,
        @Schema(example = "CONTRIBUTED", allowableValues = {"CONTRIBUTED", "NO_MATCHING_JACKPOT"}) String status,
        Instant placedAt,
        Instant processedAt) {
}
