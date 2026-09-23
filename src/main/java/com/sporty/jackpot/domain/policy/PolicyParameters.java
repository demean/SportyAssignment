package com.sporty.jackpot.domain.policy;

import com.sporty.jackpot.domain.Money;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Parameter validation and shared arithmetic of the policy records.
 */
final class PolicyParameters {

    private PolicyParameters() {
    }

    static void requirePercentage(String name, BigDecimal value) {
        requireNonNull(name, value);
        if (value.signum() < 0 || value.compareTo(Money.HUNDRED) > 0) {
            throw new JackpotConfigurationException(name + " must be within [0, 100] but was " + value.toPlainString());
        }
    }

    static void requireNonNegative(String name, BigDecimal value) {
        requireNonNull(name, value);
        if (value.signum() < 0) {
            throw new JackpotConfigurationException(name + " must be >= 0 but was " + value.toPlainString());
        }
    }

    static void requirePositive(String name, BigDecimal value) {
        requireNonNull(name, value);
        if (value.signum() <= 0) {
            throw new JackpotConfigurationException(name + " must be > 0 but was " + value.toPlainString());
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
