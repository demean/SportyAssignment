package com.sporty.jackpot.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("FixedChanceRewardPolicy")
class FixedChanceRewardPolicyTest {

    private static final BigDecimal INITIAL = new BigDecimal("1000.00");

    private static FixedChanceRewardPolicy policy(String chance) {
        return new FixedChanceRewardPolicy(new BigDecimal(chance));
    }

    @ParameterizedTest(name = "pool {0}")
    @ValueSource(strings = {"0.00", "999.99", "1000.00", "1000000.00", "1E+20"})
    @DisplayName("the win chance is the configured percentage (scale 4) whatever the pool")
    void independentOfThePool(String pool) {
        assertThat(policy("1.0").winChancePercentage(new BigDecimal(pool), INITIAL)).isEqualTo(new BigDecimal("1.0000"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"0, 0.0000", "100, 100.0000", "0.01, 0.0100", "0.0001, 0.0001", "1.00000, 1.0000"})
    void normalizedToScaleFour(String configured, String expected) {
        assertThat(policy(configured).winChancePercentage(INITIAL, INITIAL)).isEqualTo(new BigDecimal(expected));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0.00005", "0.00015"})
    @DisplayName("a chance finer than the draw resolution (0.0001 %) is rejected, not silently rounded")
    void rejectsMoreThanFourDecimals(String chance) {
        assertThatThrownBy(() -> policy(chance))
                .isInstanceOf(JackpotConfigurationException.class)
                .hasMessage("chancePercentage must have at most 4 decimals but was " + chance);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"-0.0001", "-1", "100.0001", "200"})
    void rejectsChancesOutsideZeroAndHundred(String chance) {
        assertThatThrownBy(() -> policy(chance))
                .isInstanceOf(JackpotConfigurationException.class)
                .hasMessage("chancePercentage must be within [0, 100] but was " + chance);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "1e-999999999 | chancePercentage must have at most 4 decimals but was 1E-999999999",
            "1e999999999  | chancePercentage must be within [0, 100] but was 1E+999999999"})
    @DisplayName("an extreme exponent is a configuration error quoted in scientific notation, not an OutOfMemoryError")
    void rejectsExtremeExponentsWithABoundedMessage(String chance, String message) {
        assertThatThrownBy(() -> policy(chance))
                .isInstanceOf(JackpotConfigurationException.class)
                .hasMessage(message);
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new FixedChanceRewardPolicy(null))
                .isInstanceOf(JackpotConfigurationException.class)
                .hasMessage("chancePercentage must not be null");
    }

    @ParameterizedTest(name = "initial pool {0}")
    @ValueSource(strings = {"0", "1000.00", "1E+12"})
    @DisplayName("validateFor accepts every initial pool (no cross-field constraint)")
    void validateForIsANoOp(String initialPool) {
        assertThatNoException().isThrownBy(() -> policy("1").validateFor(new BigDecimal(initialPool)));
    }
}
