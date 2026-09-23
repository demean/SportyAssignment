package com.sporty.jackpot.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("BetEvaluation")
class BetEvaluationTest {

    private static final Instant EVALUATED_AT = Instant.parse("2026-03-01T12:00:00Z");

    @ParameterizedTest(name = "{0} -> won() = {1}")
    @CsvSource({"WON, true", "LOST, false"})
    void wonReflectsTheOutcome(EvaluationOutcome outcome, boolean expected) {
        BetEvaluation evaluation = new BetEvaluation("bet-1", "user-1", "jackpot-1", outcome,
                new BigDecimal("1.0000"), expected ? new BigDecimal("1000.00") : new BigDecimal("0.00"), 3L,
                EVALUATED_AT);

        assertThat(evaluation.won()).isEqualTo(expected);
        assertThat(evaluation.jackpotCycle()).isEqualTo(3L);
        assertThat(evaluation.evaluatedAt()).isEqualTo(EVALUATED_AT);
    }

    @Test
    @DisplayName("outcomes are exactly WON and LOST (persisted as strings, DB CHECK constraint)")
    void outcomes() {
        assertThat(EvaluationOutcome.values()).extracting(Enum::name).containsExactly("WON", "LOST");
    }
}
