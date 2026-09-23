package com.sporty.jackpot.domain.model;

import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read model of a jackpot.
 *
 * @param id                 jackpot id
 * @param name               display name
 * @param initialPoolAmount  pool value at start and after every reward
 * @param currentPoolAmount  current pool value
 * @param cycle              current cycle (incremented on every reward)
 * @param contributionPolicy how bets contribute to the pool
 * @param rewardPolicy       how the win chance is computed
 * @param updatedAt          last pool change
 */
public record Jackpot(String id, String name, BigDecimal initialPoolAmount, BigDecimal currentPoolAmount, long cycle,
                      ContributionPolicy contributionPolicy, RewardPolicy rewardPolicy, Instant updatedAt) {
}
