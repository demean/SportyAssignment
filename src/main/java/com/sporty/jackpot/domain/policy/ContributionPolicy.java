package com.sporty.jackpot.domain.policy;

import com.sporty.jackpot.domain.Money;
import java.math.BigDecimal;

/**
 * How much of a stake a bet contributes to a jackpot pool. Sealed: adding a policy fails compilation until the
 * exhaustive {@code switch} in the API mapping handles it; {@code PolicyJsonTest} fails until its JSON subtype is
 * registered in {@code PolicyJson}.
 */
public sealed interface ContributionPolicy permits FixedContributionPolicy, VariableContributionPolicy {

    /**
     * Percentage (scale 4) of the stake to contribute, given the pool BEFORE this contribution.
     *
     * @param currentPool the pool before the contribution
     * @param initialPool the jackpot's initial pool
     * @return the contribution percentage in (0, 100]: never 0 %, because a bet contributing 0.00 is never drawn
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
