package com.sporty.jackpot.exception;

import java.io.Serial;

/**
 * The broker did not acknowledge a bet in time. The outcome is unknown: the client should retry with the same bet id
 * (processing is idempotent by bet id).
 */
public class BetPublishingException extends JackpotServiceException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String betId;

    public BetPublishingException(String betId, Throwable cause) {
        super(ErrorCode.BET_PUBLISH_FAILED,
                "Bet '" + betId + "' could not be confirmed by the message broker; outcome unknown - retry with the same betId",
                cause);
        this.betId = betId;
    }

    public String getBetId() {
        return betId;
    }
}
