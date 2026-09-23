package com.sporty.jackpot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.sporty.jackpot.support.StubRandomGenerator;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RewardDraw: exact integer draw nextLong(1_000_000) < chance × 10_000")
class RewardDrawTest {

    private static final long LOWEST_DRAW = 0L;
    private static final long HIGHEST_DRAW = RewardDraw.DRAW_RANGE - 1;

    private static boolean draw(String chance, long randomValue) {
        return new RewardDraw(new StubRandomGenerator(randomValue)).isWinning(new BigDecimal(chance));
    }

    @Test
    @DisplayName("draws exactly once from [0, 1_000_000)")
    void drawsOnceFromTheFullRange() {
        StubRandomGenerator random = new StubRandomGenerator(LOWEST_DRAW);

        new RewardDraw(random).isWinning(new BigDecimal("50"));

        assertThat(random.requestedBounds()).containsExactly(1_000_000L);
        assertThat(random.remaining()).isZero();
    }

    @ParameterizedTest(name = "chance {0} never wins, not even with the lowest draw")
    @ValueSource(strings = {"0", "0.0000", "0.00", "0.00004", "0.00005"})
    void zeroChanceNeverWins(String chance) {
        assertThat(draw(chance, LOWEST_DRAW)).isFalse();
    }

    @ParameterizedTest(name = "chance {0} always wins, even with the highest draw")
    @ValueSource(strings = {"100", "100.0000", "100.00000", "99.99995"})
    void fullChanceAlwaysWins(String chance) {
        assertThat(draw(chance, HIGHEST_DRAW)).isTrue();
    }

    @ParameterizedTest(name = "chance {0} -> threshold {1}: draw {1}-1 wins, draw {1} loses")
    @CsvSource({
            "0.0001,   1",
            "1,        10000",
            "12.3456,  123456",
            "50.00,    500000",
            "99.9999,  999999"
    })
    void thresholdBoundaryIsExclusive(String chance, long threshold) {
        assertThat(draw(chance, threshold - 1)).as("draw just below the threshold").isTrue();
        assertThat(draw(chance, threshold)).as("draw equal to the threshold").isFalse();
    }

    @ParameterizedTest(name = "chance {0} (scale > 4) rounds HALF_EVEN to threshold {1}")
    @CsvSource({
            "0.00015,    2",
            "0.00025,    2",
            "12.345649,  123456",
            "12.34565,   123456",
            "12.34575,   123458"
    })
    void chanceWithMoreThanFourDecimalsIsRoundedHalfEven(String chance, long threshold) {
        assertThat(draw(chance, threshold - 1)).as("draw just below the rounded threshold").isTrue();
        assertThat(draw(chance, threshold)).as("draw equal to the rounded threshold").isFalse();
    }

    @ParameterizedTest(name = "chance {0} is rejected without drawing")
    @ValueSource(strings = {"-0.0001", "-1", "100.0001", "100.00001", "1000"})
    void chanceOutsideZeroToHundredIsRejected(String chance) {
        StubRandomGenerator random = new StubRandomGenerator(LOWEST_DRAW);
        RewardDraw rewardDraw = new RewardDraw(random);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> rewardDraw.isWinning(new BigDecimal(chance)))
                .withMessage("chancePercentage must be within [0, 100] but was " + chance);
        assertThat(random.requestedBounds()).isEmpty();
        assertThat(random.remaining()).isOne();
    }
}
