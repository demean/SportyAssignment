package com.sporty.jackpot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read model of a jackpot contribution.
 *
 * @param betId                 contributing bet
 * @param userId                betting user
 * @param jackpotId             jackpot contributed to
 * @param stakeAmount           the bet amount
 * @param contributionAmount    the share of the stake added to the pool
 * @param currentJackpotAmount  the pool right after this contribution
 * @param jackpotCycle          the jackpot cycle the contribution belongs to
 * @param createdAt             when the contribution was recorded
 */
public record Contribution(String betId, String userId, String jackpotId, BigDecimal stakeAmount,
                           BigDecimal contributionAmount, BigDecimal currentJackpotAmount, long jackpotCycle,
                           Instant createdAt) {
}
