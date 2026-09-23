package com.sporty.jackpot.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ErrorCode")
class ErrorCodeTest {

    @Test
    @DisplayName("the codes are a stable client contract: exactly the ones of DESIGN.md section 8.1")
    void stableCodes() {
        assertThat(ErrorCode.values()).extracting(Enum::name).containsExactlyInAnyOrder(
                "VALIDATION_FAILED", "MALFORMED_REQUEST", "INVALID_BET",
                "BET_NOT_FOUND", "JACKPOT_NOT_FOUND", "RESOURCE_NOT_FOUND",
                "METHOD_NOT_ALLOWED",
                "NOT_ACCEPTABLE",
                "CONFLICT",
                "UNSUPPORTED_MEDIA_TYPE",
                "BET_NOT_CONTRIBUTING",
                "INTERNAL_ERROR",
                "BET_PUBLISH_FAILED", "TEMPORARILY_UNAVAILABLE");
    }
}
