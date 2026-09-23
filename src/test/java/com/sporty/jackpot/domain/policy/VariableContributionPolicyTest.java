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

@DisplayName("VariableContributionPolicy: max(min, start - decay * max(0, pool - initial) / step)")
class VariableContributionPolicyTest {

    /** Seeded jackpot-variable: 10 % decaying by 0.5 % per 1000 above 5000, floored at 1 %. */
    private static final VariableContributionPolicy VARIABLE = policy("10.0", "1.0", "0.5", "1000");
    private static final BigDecimal VARIABLE_INITIAL = new BigDecimal("5000.00");

    /** Seeded jackpot-lucky: 20 % decaying by 1 % per 100 above 100, floored at 5 %. */
    private static final VariableContributionPolicy LUCKY = policy("20.0", "5.0", "1.0", "100");
    private static final BigDecimal LUCKY_INITIAL = new BigDecimal("100.00");

    private static VariableContributionPolicy policy(String start, String min, String decay, String step) {
        return new VariableContributionPolicy(new BigDecimal(start), new BigDecimal(min), new BigDecimal(decay),
                new BigDecimal(step));
    }

    @Nested
    @DisplayName("contribution percentage")
    class Percentage {

        @ParameterizedTest(name = "pool {0} -> {1} %")
        @CsvSource({
                // at and below the initial pool: start percentage (growth clamped to 0)
                "5000.00, 10.0000",
                "4999.99, 10.0000",
                "0.00, 10.0000",
                // decay is linear in the growth, not stepwise
                "5500.00, 9.7500",
                "5999.99, 9.5000",
                // exactly at step multiples
                "6000.00, 9.5000",
                "7000.00, 9.0000",
                "15000.00, 5.0000",
                // one cent before the floor, exactly at the floor, beyond it
                "22999.99, 1.0000",
                "23000.00, 1.0000",
                "24000.00, 1.0000",
                // huge pools stay at the floor
                "1E+15, 1.0000",
                "1E+30, 1.0000"
        })
        void jackpotVariable(String pool, String expected) {
            BigDecimal percentage = VARIABLE.contributionPercentage(new BigDecimal(pool), VARIABLE_INITIAL);

            assertThat(percentage).isEqualTo(new BigDecimal(expected));
        }

        @ParameterizedTest(name = "pool {0} -> {1} %")
        @CsvSource({
                "99.99, 20.0000",
                "100.00, 20.0000",
                "150.00, 19.5000",
                "200.00, 19.0000",
                "1600.00, 5.0000",
                "1700.00, 5.0000"
        })
        void jackpotLucky(String pool, String expected) {
            assertThat(LUCKY.contributionPercentage(new BigDecimal(pool), LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("non-terminating step fractions are computed with DECIMAL64 and rounded to scale 4")
        void nonTerminatingDivision() {
            VariableContributionPolicy thirds = policy("10", "0", "1", "3");

            assertThat(thirds.contributionPercentage(BigDecimal.ONE, BigDecimal.ZERO)).isEqualTo(new BigDecimal("9.6667"));
            assertThat(thirds.contributionPercentage(new BigDecimal("2"), BigDecimal.ZERO))
                    .isEqualTo(new BigDecimal("9.3333"));
        }

        @Test
        @DisplayName("zero decay keeps the start percentage forever")
        void zeroDecay() {
            VariableContributionPolicy constant = policy("7.5", "1", "0", "100");

            assertThat(constant.contributionPercentage(new BigDecimal("1E+18"), LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal("7.5000"));
        }

        @Test
        @DisplayName("min equal to start is a constant percentage")
        void minEqualsStart() {
            VariableContributionPolicy constant = policy("3", "3", "1", "1");

            assertThat(constant.contributionPercentage(new BigDecimal("500"), LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal("3.0000"));
        }

        @Test
        @DisplayName("a zero floor lets the percentage decay to 0 but never below")
        void zeroFloor() {
            VariableContributionPolicy toZero = policy("1", "0", "1", "100");

            assertThat(toZero.contributionPercentage(new BigDecimal("200.00"), LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal("0.0000"));
            assertThat(toZero.contributionPercentage(new BigDecimal("900.00"), LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal("0.0000"));
        }
    }

    @Nested
    @DisplayName("contribution amount")
    class Amount {

        @ParameterizedTest(name = "stake {0} at pool {1} -> {2}")
        @CsvSource({
                // deterministic README demo: 20 % of 250.00
                "250.00, 100.00, 50.00",
                "250.00, 150.00, 48.75",
                "10.00, 1700.00, 0.50",
                // 5 % of 0.10 = 0.005 -> HALF_EVEN 0.00; 5 % of 0.30 = 0.015 -> 0.02
                "0.10, 1700.00, 0.00",
                "0.30, 1700.00, 0.02"
        })
        void jackpotLucky(String stake, String pool, String expected) {
            assertThat(LUCKY.contributionAmount(new BigDecimal(stake), new BigDecimal(pool), LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal(expected));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest(name = "{4}")
        @CsvSource(nullValues = "null", value = {
                "null, 1, 0.5, 1000, startPercentage must not be null",
                "-0.0001, 0, 0.5, 1000, 'startPercentage must be within [0, 100] but was -0.0001'",
                "100.0001, 1, 0.5, 1000, 'startPercentage must be within [0, 100] but was 100.0001'",
                "10, null, 0.5, 1000, minPercentage must not be null",
                "10, -1, 0.5, 1000, 'minPercentage must be within [0, 100] but was -1'",
                "100, 100.5, 0.5, 1000, 'minPercentage must be within [0, 100] but was 100.5'",
                "5, 5.0001, 0.5, 1000, minPercentage (5.0001) must not exceed startPercentage (5)",
                "10, 1, null, 1000, decayPercentage must not be null",
                "10, 1, -0.1, 1000, decayPercentage must be >= 0 but was -0.1",
                "10, 1, 0.5, null, poolIncreaseStep must not be null",
                "10, 1, 0.5, 0, poolIncreaseStep must be > 0 but was 0",
                "10, 1, 0.5, -1000, poolIncreaseStep must be > 0 but was -1000"
        })
        void rejectsInvalidParameters(BigDecimal start, BigDecimal min, BigDecimal decay, BigDecimal step,
                                      String message) {
            assertThatThrownBy(() -> new VariableContributionPolicy(start, min, decay, step))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage(message);
        }

        @ParameterizedTest(name = "start={0} min={1} decay={2} step={3}")
        @CsvSource({
                "0, 0, 0, 0.01",
                "100, 100, 0, 1",
                "100, 0, 100, 1E+9",
                "10.0, 10.0, 0.5, 1000"
        })
        void acceptsBoundaryParameters(BigDecimal start, BigDecimal min, BigDecimal decay, BigDecimal step) {
            VariableContributionPolicy policy = new VariableContributionPolicy(start, min, decay, step);

            assertThat(policy.startPercentage()).isEqualTo(start);
            assertThat(policy.minPercentage()).isEqualTo(min);
            assertThat(policy.decayPercentage()).isEqualTo(decay);
            assertThat(policy.poolIncreaseStep()).isEqualTo(step);
        }

        @ParameterizedTest(name = "initial pool {0}")
        @ValueSource(strings = {"0", "5000.00", "1E+12"})
        @DisplayName("validateFor accepts every initial pool (no cross-field constraint)")
        void validateForIsANoOp(String initialPool) {
            assertThatNoException().isThrownBy(() -> VARIABLE.validateFor(new BigDecimal(initialPool)));
        }
    }
}
