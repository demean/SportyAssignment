package com.sporty.jackpot.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.exception.ErrorCode;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

@DisplayName("ErrorHttpStatus")
class ErrorHttpStatusTest {

    /** The table of DESIGN.md §8.1. */
    private static final Map<ErrorCode, HttpStatus> EXPECTED = new EnumMap<>(Map.ofEntries(
            Map.entry(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.MALFORMED_REQUEST, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.INVALID_BET, HttpStatus.BAD_REQUEST),
            Map.entry(ErrorCode.BET_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.JACKPOT_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(ErrorCode.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED),
            Map.entry(ErrorCode.NOT_ACCEPTABLE, HttpStatus.NOT_ACCEPTABLE),
            Map.entry(ErrorCode.CONFLICT, HttpStatus.CONFLICT),
            Map.entry(ErrorCode.UNSUPPORTED_MEDIA_TYPE, HttpStatus.UNSUPPORTED_MEDIA_TYPE),
            Map.entry(ErrorCode.BET_NOT_CONTRIBUTING, HttpStatus.UNPROCESSABLE_CONTENT),
            Map.entry(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry(ErrorCode.BET_PUBLISH_FAILED, HttpStatus.SERVICE_UNAVAILABLE),
            Map.entry(ErrorCode.TEMPORARILY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE)));

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("maps every error code to the HTTP status of the design table")
    void mapsEveryCode(ErrorCode code) {
        assertThat(EXPECTED).as("DESIGN.md §8.1 lists %s", code).containsKey(code);
        assertThat(ErrorHttpStatus.of(code)).isEqualTo(EXPECTED.get(code));
    }

    @Test
    @DisplayName("the design table covers exactly the declared error codes")
    void designTableIsComplete() {
        assertThat(EXPECTED.keySet()).containsExactly(ErrorCode.values());
    }
}
