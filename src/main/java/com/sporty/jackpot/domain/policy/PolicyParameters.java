package com.sporty.jackpot.domain.policy;

import com.sporty.jackpot.domain.Money;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Parameter validation and shared arithmetic of the policy records. A rejected value is quoted with
 * {@link BigDecimal#toString()}, never {@code toPlainString()}: a stored value such as {@code 1e-999999999} has a plain
 * form of a billion digits (an {@code OutOfMemoryError} instead of a configuration error), while {@code toString()}
 * switches to scientific notation.
 */
final class PolicyParameters {

    private PolicyParameters() {
    }

    /**
     * A percentage used as configured: within [0, 100] and at most {@value Money#PERCENT_SCALE} decimals, the
     * precision the policies compute with and the draw resolves (0.0001 %). A finer value would be displayed as
     * configured but silently rounded when used (e.g. a chance of 0.00005 % could never win).
     */
    static void requirePercentage(String name, BigDecimal value) {
        requireNonNull(name, value);
        if (value.signum() < 0 || value.compareTo(Money.HUNDRED) > 0) {
            throw new JackpotConfigurationException(name + " must be within [0, 100] but was " + value);
        }
        if (value.stripTrailingZeros().scale() > Money.PERCENT_SCALE) {
            throw new JackpotConfigurationException(name + " must have at most " + Money.PERCENT_SCALE
                    + " decimals but was " + value);
        }
    }

    /**
     * A contribution percentage: a {@linkplain #requirePercentage percentage} that is strictly positive. A bet that
     * contributes 0.00 is never drawn (micro-bet guard), so with a 0 % contribution no bet would ever be drawn again:
     * the pool could neither grow nor be won, and the contributions already in it would be locked for good.
     */
    static void requirePositivePercentage(String name, BigDecimal value) {
        requirePercentage(name, value);
        if (value.signum() == 0) {
            throw new JackpotConfigurationException(name + " must be > 0 but was " + value
                    + " (a 0 % contribution is never drawn, so the jackpot could never be won)");
        }
    }

    static void requireNonNegative(String name, BigDecimal value) {
        requireNonNull(name, value);
        if (value.signum() < 0) {
            throw new JackpotConfigurationException(name + " must be >= 0 but was " + value);
        }
    }

    static void requirePositive(String name, BigDecimal value) {
        requireNonNull(name, value);
        if (value.signum() <= 0) {
            throw new JackpotConfigurationException(name + " must be > 0 but was " + value);
        }
    }

    /**
     * Number of (fractional) pool-increase steps the pool has grown above its initial value; growth below the
     * initial value is clamped to 0.
     */
    static BigDecimal poolGrowthSteps(BigDecimal pool, BigDecimal initialPool, BigDecimal poolIncreaseStep) {
        return pool.subtract(initialPool).max(BigDecimal.ZERO).divide(poolIncreaseStep, MathContext.DECIMAL64);
    }

    private static void requireNonNull(String name, BigDecimal value) {
        if (value == null) {
            throw new JackpotConfigurationException(name + " must not be null");
        }
    }
}
