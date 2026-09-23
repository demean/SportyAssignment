package com.sporty.jackpot.api.dto;

import com.sporty.jackpot.domain.model.Bet;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Request to place a bet.
 *
 * @param betId     globally unique bet id (idempotency key)
 * @param userId    user id
 * @param jackpotId jackpot id
 * @param betAmount stake, 0.01 to 1,000,000,000.00, at most 2 decimals
 */
@Schema(description = "A bet to publish")
public record PlaceBetRequest(
        @Schema(description = "Globally unique bet id; re-sending the same id is a no-op", example = "bet-1001")
        @NotBlank @Pattern(regexp = Bet.ID_REGEX) String betId,
        @Schema(description = "User id", example = "user-42")
        @NotBlank @Pattern(regexp = Bet.ID_REGEX) String userId,
        @Schema(description = "Jackpot id", example = "jackpot-lucky")
        @NotBlank @Pattern(regexp = Bet.ID_REGEX) String jackpotId,
        @Schema(description = "Stake", example = "250.00")
        @NotNull @DecimalMin("0.01") @DecimalMax("1000000000.00") @Digits(integer = 10, fraction = 2)
        BigDecimal betAmount) {
}
