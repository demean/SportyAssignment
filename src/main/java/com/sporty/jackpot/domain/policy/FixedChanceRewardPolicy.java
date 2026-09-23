package com.sporty.jackpot.domain.policy;

import com.sporty.jackpot.domain.Money;
import java.math.BigDecimal;

/**
 * Every contributing bet wins with the same fixed chance.
 *
 * @param chancePercentage win chance in percent, {@code 0 <= chance <= 100}
 */
public record FixedChanceRewardPolicy(BigDecimal chancePercentage) implements RewardPolicy {

    public FixedChanceRewardPolicy {
        PolicyParameters.requirePercentage("chancePercentage", chancePercentage);
    }

    @Override
    public BigDecimal winChancePercentage(BigDecimal poolAmount, BigDecimal initialPool) {
        return Money.normalizePercentage(chancePercentage);
    }
}
