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

@DisplayName("VariableChanceRewardPolicy: pool >= limit ? 100 : min(100, start + increase * max(0, pool - initial) / step)")
class VariableChanceRewardPolicyTest {

    /** Seeded jackpot-variable: 0.1 % growing by 0.5 % per 1000 above 5000, certain from 25000. */
    private static final VariableChanceRewardPolicy VARIABLE = policy("0.1", "0.5", "1000", "25000");
    private static final BigDecimal VARIABLE_INITIAL = new BigDecimal("5000.00");

    /** Seeded jackpot-lucky: 5 % growing by 10 % per 10 above 100, certain from 150. */
    private static final VariableChanceRewardPolicy LUCKY = policy("5.0", "10.0", "10", "150");
    private static final BigDecimal LUCKY_INITIAL = new BigDecimal("100.00");

    private static VariableChanceRewardPolicy policy(String start, String increase, String step, String limit) {
        return new VariableChanceRewardPolicy(new BigDecimal(start), new BigDecimal(increase), new BigDecimal(step),
                new BigDecimal(limit));
    }

    @Nested
    @DisplayName("win chance")
    class WinChance {

        @ParameterizedTest(name = "pool {0} -> {1} %")
        @CsvSource({
                // at and below the initial pool: start chance (growth clamped to 0)
                "5000.00, 0.1000",
                "4000.00, 0.1000",
                "0.00, 0.1000",
                // linear growth, exactly at step multiples and in between
                "5500.00, 0.3500",
                "6000.00, 0.6000",
                "7000.00, 1.1000",
                // one cent below the limit: 0.1 + 0.5 * 19.99999 = 10.099995 -> HALF_EVEN scale 4
                "24999.99, 10.1000",
                // at / beyond the limit: certain
                "25000.00, 100.0000",
                "25000.01, 100.0000",
                "1E+20, 100.0000"
        })
        void jackpotVariable(String pool, String expected) {
            BigDecimal chance = VARIABLE.winChancePercentage(new BigDecimal(pool), VARIABLE_INITIAL);

            assertThat(chance).isEqualTo(new BigDecimal(expected));
        }

        @ParameterizedTest(name = "pool {0} -> {1} %")
        @CsvSource({
                "90.00, 5.0000",
                "100.00, 5.0000",
                "105.00, 10.0000",
                "110.00, 15.0000",
                "149.99, 54.9900",
                // the README demo: a 250.00 bet pushes the pool to exactly the limit -> 100 %
                "150.00, 100.0000"
        })
        void jackpotLucky(String pool, String expected) {
            assertThat(LUCKY.winChancePercentage(new BigDecimal(pool), LUCKY_INITIAL)).isEqualTo(new BigDecimal(expected));
        }

        @ParameterizedTest(name = "pool {0} -> {1} %")
        @CsvSource({
                "110.00, 80.0000",
                "115.00, 95.0000",
                "116.66, 99.9800",
                // 50 + 30 * 1.667 = 100.01 -> capped
                "116.67, 100.0000",
                "120.00, 100.0000",
                "999.99, 100.0000"
        })
        @DisplayName("is capped at 100 % below the limit")
        void cappedAtHundred(String pool, String expected) {
            VariableChanceRewardPolicy steep = policy("50", "30", "10", "1000");

            assertThat(steep.winChancePercentage(new BigDecimal(pool), LUCKY_INITIAL)).isEqualTo(new BigDecimal(expected));
        }

        @Test
        @DisplayName("non-terminating step fractions are computed with DECIMAL64 and rounded to scale 4")
        void nonTerminatingDivision() {
            VariableChanceRewardPolicy thirds = policy("0", "1", "3", "1000");

            assertThat(thirds.winChancePercentage(BigDecimal.ONE, BigDecimal.ZERO)).isEqualTo(new BigDecimal("0.3333"));
            assertThat(thirds.winChancePercentage(new BigDecimal("2"), BigDecimal.ZERO))
                    .isEqualTo(new BigDecimal("0.6667"));
        }

        @Test
        @DisplayName("zero increase keeps the start chance until the limit")
        void zeroIncrease() {
            VariableChanceRewardPolicy flat = policy("2.5", "0", "10", "150");

            assertThat(flat.winChancePercentage(new BigDecimal("149.99"), LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal("2.5000"));
            assertThat(flat.winChancePercentage(new BigDecimal("150"), LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal("100.0000"));
        }

        @Test
        @DisplayName("a zero start chance never wins at the initial pool")
        void zeroStart() {
            assertThat(policy("0", "1", "10", "150").winChancePercentage(LUCKY_INITIAL, LUCKY_INITIAL))
                    .isEqualTo(new BigDecimal("0.0000"));
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @ParameterizedTest(name = "{4}")
        @CsvSource(nullValues = "null", value = {
                "null, 0.5, 1000, 25000, startChancePercentage must not be null",
                "-0.01, 0.5, 1000, 25000, 'startChancePercentage must be within [0, 100] but was -0.01'",
                "100.01, 0.5, 1000, 25000, 'startChancePercentage must be within [0, 100] but was 100.01'",
                "0.1, null, 1000, 25000, chanceIncreasePercentage must not be null",
                "0.1, -0.5, 1000, 25000, chanceIncreasePercentage must be >= 0 but was -0.5",
                "0.1, 0.5, null, 25000, poolIncreaseStep must not be null",
                "0.1, 0.5, 0, 25000, poolIncreaseStep must be > 0 but was 0",
                "0.1, 0.5, -1, 25000, poolIncreaseStep must be > 0 but was -1",
                "0.1, 0.5, 1000, null, poolLimit must not be null",
                "0.1, 0.5, 1000, 0, poolLimit must be > 0 but was 0",
                "0.1, 0.5, 1000, -25000, poolLimit must be > 0 but was -25000"
        })
        void rejectsInvalidParameters(BigDecimal start, BigDecimal increase, BigDecimal step, BigDecimal limit,
                                      String message) {
            assertThatThrownBy(() -> new VariableChanceRewardPolicy(start, increase, step, limit))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage(message);
        }

        @ParameterizedTest(name = "start={0} increase={1} step={2} limit={3}")
        @CsvSource({
                "0, 0, 0.01, 0.01",
                "100, 1000, 1, 1E+15",
                "0.1, 0.5, 1000, 25000"
        })
        void acceptsBoundaryParameters(BigDecimal start, BigDecimal increase, BigDecimal step, BigDecimal limit) {
            VariableChanceRewardPolicy policy = new VariableChanceRewardPolicy(start, increase, step, limit);

            assertThat(policy.startChancePercentage()).isEqualTo(start);
            assertThat(policy.chanceIncreasePercentage()).isEqualTo(increase);
            assertThat(policy.poolIncreaseStep()).isEqualTo(step);
            assertThat(policy.poolLimit()).isEqualTo(limit);
        }

        @ParameterizedTest(name = "limit {0} > initial {1}")
        @CsvSource({"150, 100.00", "100.01, 100.00", "25000, 5000.00", "0.01, 0"})
        void validateForAcceptsALimitAboveTheInitialPool(String limit, String initialPool) {
            VariableChanceRewardPolicy policy = policy("1", "1", "10", limit);

            assertThatNoException().isThrownBy(() -> policy.validateFor(new BigDecimal(initialPool)));
        }

        @ParameterizedTest(name = "limit {0} <= initial {1}")
        @CsvSource({"100, 100.00", "100.00, 100.00", "99.99, 100.00", "150, 1000.00"})
        void validateForRejectsALimitNotAboveTheInitialPool(String limit, String initialPool) {
            VariableChanceRewardPolicy policy = policy("1", "1", "10", limit);

            assertThatThrownBy(() -> policy.validateFor(new BigDecimal(initialPool)))
                    .isInstanceOf(JackpotConfigurationException.class)
                    .hasMessage("poolLimit (" + limit + ") must be greater than the initial pool (" + initialPool + ")");
        }
    }
}
