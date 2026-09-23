package com.sporty.jackpot.domain.model;

/**
 * Result of processing one bet. {@code contribution} and {@code evaluation} are non-null only for
 * {@link ProcessingStatus#PROCESSED}; use the factory methods.
 *
 * @param status       processing status
 * @param betId        processed bet id
 * @param contribution the recorded contribution, or {@code null}
 * @param evaluation   the recorded evaluation, or {@code null}
 */
public record ProcessingResult(ProcessingStatus status, String betId, Contribution contribution,
                               BetEvaluation evaluation) {

    /**
     * A bet that contributed and was evaluated.
     *
     * @param contribution the recorded contribution
     * @param evaluation   the recorded evaluation
     * @return a {@link ProcessingStatus#PROCESSED} result
     */
    public static ProcessingResult processed(Contribution contribution, BetEvaluation evaluation) {
        return new ProcessingResult(ProcessingStatus.PROCESSED, contribution.betId(), contribution, evaluation);
    }

    /**
     * A bet id that had already been processed.
     *
     * @param betId the duplicate bet id
     * @return a {@link ProcessingStatus#DUPLICATE} result
     */
    public static ProcessingResult duplicate(String betId) {
        return new ProcessingResult(ProcessingStatus.DUPLICATE, betId, null, null);
    }

    /**
     * A bet referencing a jackpot that does not exist.
     *
     * @param betId the bet id
     * @return a {@link ProcessingStatus#NO_MATCHING_JACKPOT} result
     */
    public static ProcessingResult noMatchingJackpot(String betId) {
        return new ProcessingResult(ProcessingStatus.NO_MATCHING_JACKPOT, betId, null, null);
    }
}
