package com.sporty.jackpot.exception;

import java.io.Serial;

/**
 * A jackpot (or its contribution/reward policy) is misconfigured. Deterministic, never retryable.
 */
public class JackpotConfigurationException extends JackpotServiceException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JackpotConfigurationException(String message) {
        super(ErrorCode.INTERNAL_ERROR, message);
    }

    public JackpotConfigurationException(String message, Throwable cause) {
        super(ErrorCode.INTERNAL_ERROR, message, cause);
    }
}
