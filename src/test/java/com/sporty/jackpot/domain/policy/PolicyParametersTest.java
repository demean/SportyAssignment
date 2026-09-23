package com.sporty.jackpot.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PolicyParameters")
class PolicyParametersTest {

    @Nested
    @DisplayName("requirePercentage: [0, 100], at most 4 decimals")
    class RequirePercentage {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"0", "0.0000", "0.0001", "5.0", "99.9999", "100", "100.00", "1.00000000"})
        void accepts(String value) {
            assertThatNoException().isThrownBy(() -> PolicyParameters.requirePercentage("p", new BigDecimal(value)));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"-0.0001, -0.0001", "-100, -100", "100.0001, 100.0001", "1E+3, 1E+3",
                // extreme exponents are quoted in scientific notation, never expanded (a billion digits)
                "1e999999999, 1E+999999999", "-1e999999999, -1E+999999999"})
        void rejects(String value, String quoted) {
            assertThatThrownBy(() -> PolicyParameters.requirePercentage("p", new BigDecimal(value)))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("p must be within [0, 100] but was " + quoted);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"0.00005, 0.00005", "0.00001, 0.00001", "99.99999, 99.99999", "12.345678, 12.345678",
                // a stored 1e-999999999 used to build a billion-character message: OutOfMemoryError
                "1e-999999999, 1E-999999999"})
        @DisplayName("more than 4 decimals would be rounded when used: rejected, quoted in bounded form")
        void rejectsMoreThanFourDecimals(String value, String quoted) {
            assertThatThrownBy(() -> PolicyParameters.requirePercentage("p", new BigDecimal(value)))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("p must have at most 4 decimals but was " + quoted);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> PolicyParameters.requirePercentage("p", null))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("p must not be null");
        }
    }

    @Nested
    @DisplayName("requirePositivePercentage: a percentage within (0, 100] (contribution percentages)")
    class RequirePositivePercentage {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"0.0001", "5.0", "99.9999", "100", "1.00000000"})
        void accepts(String value) {
            assertThatNoException()
                    .isThrownBy(() -> PolicyParameters.requirePositivePercentage("c", new BigDecimal(value)));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"0, 0", "0.0000, 0.0000", "0E-999999999, 0E-999999999"})
        @DisplayName("0 % is rejected: a bet contributing 0.00 is never drawn, so the jackpot could never be won")
        void rejectsZero(String value, String quoted) {
            assertThatThrownBy(() -> PolicyParameters.requirePositivePercentage("c", new BigDecimal(value)))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("c must be > 0 but was " + quoted
                            + " (a 0 % contribution is never drawn, so the jackpot could never be won)");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "-0.0001  | c must be within [0, 100] but was -0.0001",
                "100.0001 | c must be within [0, 100] but was 100.0001",
                "0.00001  | c must have at most 4 decimals but was 0.00001"})
        @DisplayName("applies every percentage rule first")
        void appliesThePercentageRules(String value, String message) {
            assertThatThrownBy(() -> PolicyParameters.requirePositivePercentage("c", new BigDecimal(value)))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage(message);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> PolicyParameters.requirePositivePercentage("c", null))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("c must not be null");
        }
    }

    @Nested
    @DisplayName("requireNonNegative: >= 0")
    class RequireNonNegative {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"0", "0.0001", "1E+30"})
        void accepts(String value) {
            assertThatNoException().isThrownBy(() -> PolicyParameters.requireNonNegative("d", new BigDecimal(value)));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"-0.0001, -0.0001", "-1e999999999, -1E+999999999", "-1e-999999999, -1E-999999999"})
        void rejectsNegative(String value, String quoted) {
            assertThatThrownBy(() -> PolicyParameters.requireNonNegative("d", new BigDecimal(value)))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("d must be >= 0 but was " + quoted);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> PolicyParameters.requireNonNegative("d", null))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("d must not be null");
        }
    }

    @Nested
    @DisplayName("requirePositive: > 0")
    class RequirePositive {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"0.0001", "1", "1E+30"})
        void accepts(String value) {
            assertThatNoException().isThrownBy(() -> PolicyParameters.requirePositive("s", new BigDecimal(value)));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"0, 0", "0.00, 0.00", "-0.0001, -0.0001", "-1000, -1000", "-1e999999999, -1E+999999999",
                "0E-999999999, 0E-999999999"})
        void rejectsZeroAndNegative(String value, String quoted) {
            assertThatThrownBy(() -> PolicyParameters.requirePositive("s", new BigDecimal(value)))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("s must be > 0 but was " + quoted);
        }

        @Test
        void rejectsNull() {
            assertThatThrownBy(() -> PolicyParameters.requirePositive("s", null))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("s must not be null");
        }
    }

    @Nested
    @DisplayName("poolGrowthSteps: max(0, pool - initial) / step")
    class PoolGrowthSteps {

        @ParameterizedTest(name = "pool {0}, initial {1}, step {2} -> {3}")
        @CsvSource({
                // growth below the initial pool is clamped to 0
                "0.00, 100.00, 10, 0",
                "99.99, 100.00, 10, 0",
                "100.00, 100.00, 10, 0",
                // exact multiples and fractions
                "110.00, 100.00, 10, 1",
                "105.00, 100.00, 10, 0.5",
                "100.01, 100.00, 10, 0.001",
                "1100.00, 100.00, 10, 100",
                // non-terminating quotient: DECIMAL64 (16 significant digits), no ArithmeticException
                "1, 0, 3, 0.3333333333333333",
                "2, 0, 3, 0.6666666666666667",
                // huge pools
                "1E+30, 0, 1, 1E+30"
        })
        void computesFractionalSteps(String pool, String initial, String step, String expected) {
            BigDecimal steps = PolicyParameters.poolGrowthSteps(new BigDecimal(pool), new BigDecimal(initial),
                    new BigDecimal(step));

            assertThat(steps).isEqualByComparingTo(expected);
            assertThat(steps.signum()).isNotNegative();
        }
    }
}
