package com.sporty.jackpot.domain.policy;

import java.math.BigDecimal;

/**
 * How likely a contributing bet wins the jackpot. Sealed: adding a policy fails compilation until every exhaustive
 * {@code switch} (API mapping, JSON subtypes) handles it.
 */
public sealed interface RewardPolicy permits FixedChanceRewardPolicy, VariableChanceRewardPolicy {

    /**
     * Win chance in percent [0, 100], scale 4, for the pool right after the bet's contribution.
     *
     * @param poolAmount  the pool after the contribution
     * @param initialPool the jackpot's initial pool
     * @return the win chance in percent
     */
    BigDecimal winChancePercentage(BigDecimal poolAmount, BigDecimal initialPool);

    /**
     * Cross-field validation against the jackpot's initial pool.
     *
     * @param initialPool the jackpot's initial pool
     * @throws com.sporty.jackpot.exception.JackpotConfigurationException when inconsistent
     */
    default void validateFor(BigDecimal initialPool) {
    }
}
