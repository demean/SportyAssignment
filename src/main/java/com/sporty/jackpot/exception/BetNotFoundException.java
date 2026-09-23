package com.sporty.jackpot.exception;

import java.io.Serial;

/**
 * No processed bet exists for the given id (unknown, or not consumed from Kafka yet).
 */
public class BetNotFoundException extends JackpotServiceException {

    @Serial
    private static final long serialVersionUID = 1L;

    public BetNotFoundException(String betId) {
        super(ErrorCode.BET_NOT_FOUND, "Bet '" + betId + "' was not found (unknown or not processed yet)");
    }
}
