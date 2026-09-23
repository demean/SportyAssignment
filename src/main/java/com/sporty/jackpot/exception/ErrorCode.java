package com.sporty.jackpot.exception;

/**
 * Stable, machine-readable error codes exposed as the {@code code} property of every RFC 9457 problem response.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    INVALID_BET,
    BET_NOT_FOUND,
    JACKPOT_NOT_FOUND,
    RESOURCE_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    NOT_ACCEPTABLE,
    CONFLICT,
    UNSUPPORTED_MEDIA_TYPE,
    BET_NOT_CONTRIBUTING,
    INTERNAL_ERROR,
    BET_PUBLISH_FAILED,
    TEMPORARILY_UNAVAILABLE
}
