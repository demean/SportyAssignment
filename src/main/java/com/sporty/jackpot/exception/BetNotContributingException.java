package com.sporty.jackpot.exception;

import java.io.Serial;

/**
 * The bet was processed but did not contribute to any jackpot (no matching jackpot), so it has no contribution and
 * no evaluation.
 */
public class BetNotContributingException extends JackpotServiceException {

    @Serial
    private static final long serialVersionUID = 1L;

    public BetNotContributingException(String betId) {
        super(ErrorCode.BET_NOT_CONTRIBUTING,
                "Bet '" + betId + "' was processed but did not contribute to a jackpot (no matching jackpot)");
    }
}
