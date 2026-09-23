package com.sporty.jackpot.domain.policy;

import com.sporty.jackpot.domain.Money;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The win chance starts at {@code startChancePercentage} and grows by {@code chanceIncreasePercentage} for every
 * {@code poolIncreaseStep} the pool has grown above its initial value (capped at 100 %); once the pool reaches
 * {@code poolLimit} the chance is 100 %.
 *
 * @param startChancePercentage    chance while the pool is at (or below) its initial value, in [0, 100]
 * @param chanceIncreasePercentage increase per step, {@code >= 0}
 * @param poolIncreaseStep         pool growth per increase step, {@code > 0}
 * @param poolLimit                pool value from which the bet always wins, {@code > 0} and {@code > initialPool}
 */
public record VariableChanceRewardPolicy(BigDecimal startChancePercentage, BigDecimal chanceIncreasePercentage,
                                         BigDecimal poolIncreaseStep, BigDecimal poolLimit) implements RewardPolicy {

    private static final BigDecimal CERTAIN = Money.normalizePercentage(Money.HUNDRED);

    public VariableChanceRewardPolicy {
        PolicyParameters.requirePercentage("startChancePercentage", startChancePercentage);
        PolicyParameters.requireNonNegative("chanceIncreasePercentage", chanceIncreasePercentage);
        PolicyParameters.requirePositive("poolIncreaseStep", poolIncreaseStep);
        PolicyParameters.requirePositive("poolLimit", poolLimit);
    }

    @Override
    public BigDecimal winChancePercentage(BigDecimal poolAmount, BigDecimal initialPool) {
        if (poolAmount.compareTo(poolLimit) >= 0) {
            return CERTAIN;
        }
        BigDecimal steps = PolicyParameters.poolGrowthSteps(poolAmount, initialPool, poolIncreaseStep);
        BigDecimal chance = startChancePercentage.add(chanceIncreasePercentage.multiply(steps, MathContext.DECIMAL64),
                MathContext.DECIMAL64);
        return Money.normalizePercentage(chance.min(Money.HUNDRED));
    }

    @Override
    public void validateFor(BigDecimal initialPool) {
        if (poolLimit.compareTo(initialPool) <= 0) {
            throw new JackpotConfigurationException("poolLimit (" + poolLimit.toPlainString()
                    + ") must be greater than the initial pool (" + initialPool.toPlainString() + ")");
        }
    }
}
