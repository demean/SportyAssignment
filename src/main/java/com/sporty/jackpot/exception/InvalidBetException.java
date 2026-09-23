package com.sporty.jackpot.exception;

import java.io.Serial;

/**
 * A bet violates a domain invariant (malformed id, non-positive or over-precise amount, ...). Never retryable.
 */
public class InvalidBetException extends JackpotServiceException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidBetException(String message) {
        super(ErrorCode.INVALID_BET, message);
    }
}
