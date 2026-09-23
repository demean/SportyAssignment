package com.sporty.jackpot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A bet's jackpot contribution.
 *
 * @param betId                bet id
 * @param userId               user id
 * @param jackpotId            jackpot id
 * @param stakeAmount          stake
 * @param contributionAmount   share of the stake added to the pool
 * @param currentJackpotAmount pool right after this contribution
 * @param createdAt            when the contribution was recorded
 */
@Schema(description = "A bet's contribution to a jackpot pool")
public record ContributionResponse(
        @Schema(example = "bet-1001") String betId,
        @Schema(example = "user-42") String userId,
        @Schema(example = "jackpot-lucky") String jackpotId,
        @Schema(example = "250.00") BigDecimal stakeAmount,
        @Schema(example = "50.00") BigDecimal contributionAmount,
        @Schema(description = "Pool right after this contribution", example = "150.00") BigDecimal currentJackpotAmount,
        Instant createdAt) {
}
