package com.sporty.jackpot.domain.policy;

import com.sporty.jackpot.domain.Money;
import java.math.BigDecimal;

/**
 * Every bet contributes a fixed percentage of its stake.
 *
 * @param percentage contribution percentage, {@code 0 <= percentage <= 100}
 */
public record FixedContributionPolicy(BigDecimal percentage) implements ContributionPolicy {

    public FixedContributionPolicy {
        PolicyParameters.requirePercentage("percentage", percentage);
    }

    @Override
    public BigDecimal contributionPercentage(BigDecimal currentPool, BigDecimal initialPool) {
        return Money.normalizePercentage(percentage);
    }
}
