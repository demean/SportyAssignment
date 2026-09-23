package com.sporty.jackpot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A jackpot and its configuration.
 *
 * @param id                 jackpot id
 * @param name               display name
 * @param initialPoolAmount  pool at start and after every reward
 * @param currentPoolAmount  current pool
 * @param cycle              current cycle (incremented on every reward)
 * @param contributionPolicy contribution configuration
 * @param rewardPolicy       reward configuration
 * @param updatedAt          last pool change
 */
@Schema(description = "A jackpot")
public record JackpotResponse(
        @Schema(example = "jackpot-lucky") String id,
        @Schema(example = "Lucky Demo") String name,
        @Schema(example = "100.00") BigDecimal initialPoolAmount,
        @Schema(example = "100.00") BigDecimal currentPoolAmount,
        @Schema(example = "1") long cycle,
        PolicyResponse contributionPolicy,
        PolicyResponse rewardPolicy,
        Instant updatedAt) {
}
