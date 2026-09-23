package com.sporty.jackpot.domain.model;

/**
 * Persisted status of a processed bet.
 */
public enum BetStatus {
    /** The bet matched a jackpot, contributed to it and was evaluated. */
    CONTRIBUTED,
    /** The referenced jackpot does not exist: no contribution, no evaluation. */
    NO_MATCHING_JACKPOT
}
