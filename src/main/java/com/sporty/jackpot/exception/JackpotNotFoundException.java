package com.sporty.jackpot.exception;

import java.io.Serial;

/**
 * No jackpot exists for the given id.
 */
public class JackpotNotFoundException extends JackpotServiceException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JackpotNotFoundException(String jackpotId) {
        super(ErrorCode.JACKPOT_NOT_FOUND, "Jackpot '" + jackpotId + "' was not found");
    }
}
