package com.sporty.jackpot.exception;

import java.io.Serial;

/**
 * Base class of all business exceptions of the service; each one carries the {@link ErrorCode} reported to clients.
 */
public abstract class JackpotServiceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    protected JackpotServiceException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected JackpotServiceException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
