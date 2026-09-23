package com.sporty.jackpot.domain.policy;

import com.sporty.jackpot.domain.Money;
import java.math.BigDecimal;

/**
 * How much of a stake a bet contributes to a jackpot pool. Sealed: adding a policy fails compilation until every
 * exhaustive {@code switch} (API mapping, JSON subtypes) handles it.
 */
public sealed interface ContributionPolicy permits FixedContributionPolicy, VariableContributionPolicy {

    /**
     * Percentage (scale 4) of the stake to contribute, given the pool BEFORE this contribution.
     *
     * @param currentPool the pool before the contribution
     * @param initialPool the jackpot's initial pool
     * @return the contribution percentage in [0, 100]
     */
    BigDecimal contributionPercentage(BigDecimal currentPool, BigDecimal initialPool);

    /**
     * Contribution amount (scale 2) for a stake.
     *
     * @param stake       the bet amount
     * @param currentPool the pool before the contribution
     * @param initialPool the jackpot's initial pool
     * @return {@code stake × contributionPercentage / 100}, rounded HALF_EVEN to cents
     */
    default BigDecimal contributionAmount(BigDecimal stake, BigDecimal currentPool, BigDecimal initialPool) {
        return Money.percentageOf(stake, contributionPercentage(currentPool, initialPool));
    }

    /**
     * Cross-field validation against the jackpot's initial pool.
     *
     * @param initialPool the jackpot's initial pool
     * @throws com.sporty.jackpot.exception.JackpotConfigurationException when inconsistent
     */
    default void validateFor(BigDecimal initialPool) {
    }
}
