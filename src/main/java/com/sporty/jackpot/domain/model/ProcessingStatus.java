package com.sporty.jackpot.domain.model;

/**
 * Outcome of processing one consumed bet.
 */
public enum ProcessingStatus {
    /** Contribution and evaluation were recorded. */
    PROCESSED,
    /** The bet id was already processed; nothing was changed. */
    DUPLICATE,
    /** The referenced jackpot does not exist; the bet was stored without contribution. */
    NO_MATCHING_JACKPOT
}
