package com.sporty.jackpot.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;

/**
 * A contribution or reward policy.
 *
 * @param type       {@code FIXED} or {@code VARIABLE}
 * @param parameters policy parameters in a deterministic order
 */
@Schema(description = "A contribution or reward policy")
public record PolicyResponse(
        @Schema(example = "VARIABLE", allowableValues = {"FIXED", "VARIABLE"}) String type,
        @Schema(example = "{\"startPercentage\":20.0,\"minPercentage\":5.0,\"decayPercentage\":1.0,"
                + "\"poolIncreaseStep\":100}") Map<String, BigDecimal> parameters) {
}
