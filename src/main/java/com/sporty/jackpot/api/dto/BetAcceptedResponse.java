package com.sporty.jackpot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Acknowledgement of a published bet; processing happens asynchronously.
 *
 * @param betId      bet id
 * @param jackpotId  jackpot id
 * @param status     always {@code ACCEPTED}
 * @param acceptedAt when the bet was accepted
 */
@Schema(description = "The bet was acknowledged by the broker and will be processed asynchronously")
public record BetAcceptedResponse(
        @Schema(example = "bet-1001") String betId,
        @Schema(example = "jackpot-lucky") String jackpotId,
        @Schema(example = "ACCEPTED") String status,
        @Schema(description = "When the bet was accepted (UTC)") Instant acceptedAt) {
}
