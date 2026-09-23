package com.sporty.jackpot.api.error;

import com.sporty.jackpot.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * HTTP status of every {@link ErrorCode}. The switch is exhaustive: a new code fails compilation until mapped.
 */
public final class ErrorHttpStatus {

    private ErrorHttpStatus() {
    }

    /**
     * @param code the error code
     * @return the HTTP status reported for it
     */
    public static HttpStatus of(ErrorCode code) {
        return switch (code) {
            case VALIDATION_FAILED, MALFORMED_REQUEST, INVALID_BET -> HttpStatus.BAD_REQUEST;
            case BET_NOT_FOUND, JACKPOT_NOT_FOUND, RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> HttpStatus.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> HttpStatus.NOT_ACCEPTABLE;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNSUPPORTED_MEDIA_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case BET_NOT_CONTRIBUTING -> HttpStatus.UNPROCESSABLE_CONTENT;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case BET_PUBLISH_FAILED, TEMPORARILY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
