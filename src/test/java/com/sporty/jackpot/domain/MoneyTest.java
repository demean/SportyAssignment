package com.sporty.jackpot.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("Money")
class MoneyTest {

    @Test
    @DisplayName("constants: amounts scale 2, percentages scale 4, banker's rounding")
    void constants() {
        assertThat(Money.SCALE).isEqualTo(2);
        assertThat(Money.PERCENT_SCALE).isEqualTo(4);
        assertThat(Money.ROUNDING).isEqualTo(RoundingMode.HALF_EVEN);
        assertThat(Money.HUNDRED).isEqualByComparingTo("100");
        assertThat(Money.ZERO).isEqualTo(new BigDecimal("0.00"));
        assertThat(Money.ZERO_PERCENT).isEqualTo(new BigDecimal("0.0000"));
    }

    @Nested
    @DisplayName("normalize")
    class Normalize {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                // HALF_EVEN ties go to the even neighbour
                "0.005, 0.00",
                "0.015, 0.02",
                "0.025, 0.02",
                "0.035, 0.04",
                "2.675, 2.68",
                "-0.015, -0.02",
                "-0.025, -0.02",
                // not a tie: ordinary rounding
                "1.0049, 1.00",
                "1.0051, 1.01",
                // scale is raised, never lowered below 2
                "10, 10.00",
                "10.5, 10.50",
                "1E+3, 1000.00",
                "12345678901234567.895, 12345678901234567.90"
        })
        void roundsHalfEvenToScaleTwo(BigDecimal amount, String expected) {
            BigDecimal normalized = Money.normalize(amount);

            assertThat(normalized).isEqualTo(new BigDecimal(expected));
            assertThat(normalized.scale()).isEqualTo(2);
        }

        @Test
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> Money.normalize(null)).withMessage("amount");
        }
    }

    @Nested
    @DisplayName("percentageOf")
    class PercentageOf {

        @ParameterizedTest(name = "{1} % of {0} = {2}")
        @CsvSource({
                "250.00, 20.0000, 50.00",
                "100.00, 5, 5.00",
                "100.00, 0, 0.00",
                "1000000000.00, 100, 1000000000.00",
                "10.00, 2.5, 0.25",
                // cent ties are rounded HALF_EVEN
                "0.10, 5, 0.00",
                "0.30, 5, 0.02",
                "0.50, 5, 0.02",
                "0.01, 50, 0.00",
                "0.03, 50, 0.02",
                // micro stake rounds to nothing
                "0.01, 5, 0.00",
                "0.01, 0.0001, 0.00",
                // non-tie rounding
                "33.33, 33.3333, 11.11"
        })
        void computesTheShareRoundedToCents(BigDecimal amount, BigDecimal percentage, String expected) {
            BigDecimal share = Money.percentageOf(amount, percentage);

            assertThat(share).isEqualTo(new BigDecimal(expected));
            assertThat(share.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("normalizePercentage")
    class NormalizePercentage {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "5, 5.0000",
                "1.0, 1.0000",
                "0.00005, 0.0000",
                "0.00015, 0.0002",
                "0.00025, 0.0002",
                "99.99995, 100.0000",
                "12.345678, 12.3457"
        })
        void roundsHalfEvenToScaleFour(BigDecimal percentage, String expected) {
            BigDecimal normalized = Money.normalizePercentage(percentage);

            assertThat(normalized).isEqualTo(new BigDecimal(expected));
            assertThat(normalized.scale()).isEqualTo(4);
        }

        @Test
        void rejectsNull() {
            assertThatNullPointerException().isThrownBy(() -> Money.normalizePercentage(null))
                    .withMessage("percentage");
        }
    }
}
