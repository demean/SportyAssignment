package com.sporty.jackpot.domain.policy;

import com.sporty.jackpot.domain.Money;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The contribution percentage starts at {@code startPercentage} and decreases by {@code decayPercentage} for every
 * {@code poolIncreaseStep} the pool has grown above its initial value, floored at {@code minPercentage}:
 * {@code max(min, start − decay × max(0, pool − initial) / step)}.
 *
 * @param startPercentage  percentage while the pool is at (or below) its initial value, in [minPercentage, 100]
 * @param minPercentage    floor, in (0, startPercentage]: a floor of 0 % would stop every draw once reached
 * @param decayPercentage  decrease per step, {@code >= 0}
 * @param poolIncreaseStep pool growth per decay step, {@code > 0}
 */
public record VariableContributionPolicy(BigDecimal startPercentage, BigDecimal minPercentage,
                                         BigDecimal decayPercentage, BigDecimal poolIncreaseStep)
        implements ContributionPolicy {

    public VariableContributionPolicy {
        PolicyParameters.requirePercentage("startPercentage", startPercentage);
        PolicyParameters.requirePositivePercentage("minPercentage", minPercentage);
        if (minPercentage.compareTo(startPercentage) > 0) {
            throw new JackpotConfigurationException("minPercentage (" + minPercentage
                    + ") must not exceed startPercentage (" + startPercentage + ")");
        }
        PolicyParameters.requireNonNegative("decayPercentage", decayPercentage);
        PolicyParameters.requirePositive("poolIncreaseStep", poolIncreaseStep);
    }

    @Override
    public BigDecimal contributionPercentage(BigDecimal currentPool, BigDecimal initialPool) {
        BigDecimal steps = PolicyParameters.poolGrowthSteps(currentPool, initialPool, poolIncreaseStep);
        BigDecimal decayed = startPercentage.subtract(decayPercentage.multiply(steps, MathContext.DECIMAL64),
                MathContext.DECIMAL64);
        return Money.normalizePercentage(decayed.max(minPercentage));
    }
}
