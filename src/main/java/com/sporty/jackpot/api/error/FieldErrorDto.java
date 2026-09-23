package com.sporty.jackpot.api.error;

/**
 * One validation error of a {@code VALIDATION_FAILED} problem response.
 *
 * @param field   the invalid field or parameter
 * @param message what is wrong with it
 */
public record FieldErrorDto(String field, String message) {
}
