package com.sporty.jackpot.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("FixedContributionPolicy")
class FixedContributionPolicyTest {

    private static final BigDecimal INITIAL = new BigDecimal("1000.00");

    private static FixedContributionPolicy policy(String percentage) {
        return new FixedContributionPolicy(new BigDecimal(percentage));
    }

    @Nested
    @DisplayName("contribution percentage")
    class Percentage {

        @ParameterizedTest(name = "pool {0}")
        @ValueSource(strings = {"0.00", "999.99", "1000.00", "1000.01", "5000.00", "1E+20"})
        @DisplayName("is the configured percentage (scale 4) whatever the pool")
        void independentOfThePool(String pool) {
            BigDecimal percentage = policy("5.0").contributionPercentage(new BigDecimal(pool), INITIAL);

            assertThat(percentage).isEqualTo(new BigDecimal("5.0000"));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"0, 0.0000", "100, 100.0000", "2.5, 2.5000", "0.00005, 0.0000", "0.00015, 0.0002"})
        void normalizedToScaleFourHalfEven(String configured, String expected) {
            assertThat(policy(configured).contributionPercentage(INITIAL, INITIAL)).isEqualTo(new BigDecimal(expected));
        }
    }

    @Nested
    @DisplayName("contribution amount")
    class Amount {

        @ParameterizedTest(name = "{1} % of {0} = {2}")
        @CsvSource({
                "100.00, 5.0, 5.00",
                "250.00, 20, 50.00",
                "1000000000.00, 100, 1000000000.00",
                "123.45, 0, 0.00",
                // cent ties HALF_EVEN, micro stakes contribute nothing
                "0.10, 5, 0.00",
                "0.30, 5, 0.02",
                "0.01, 5, 0.00"
        })
        void stakeTimesPercentageRoundedToCents(String stake, String percentage, String expected) {
            BigDecimal amount = policy(percentage).contributionAmount(new BigDecimal(stake), INITIAL, INITIAL);

            assertThat(amount).isEqualTo(new BigDecimal(expected));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"0", "0.0000", "50", "100", "100.0000"})
        void acceptsPercentagesWithinZeroAndHundred(String percentage) {
            assertThat(policy(percentage).percentage()).isEqualByComparingTo(percentage);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"-0.0001", "-5", "100.0001", "101"})
        void rejectsPercentagesOutsideZeroAndHundred(String percentage) {
            assertThatThrownBy(() -> policy(percentage))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("percentage must be within [0, 100] but was " + percentage)
                    .extracting(e -> ((JackpotConfigurationException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> new FixedContributionPolicy(null))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("percentage must not be null");
        }

        @ParameterizedTest(name = "initial pool {0}")
        @ValueSource(strings = {"0", "1000.00", "1E+12"})
        @DisplayName("validateFor accepts every initial pool (no cross-field constraint)")
        void validateForIsANoOp(String initialPool) {
            assertThatNoException().isThrownBy(() -> policy("5").validateFor(new BigDecimal(initialPool)));
        }
    }
}
