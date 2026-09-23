package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.Money;
import java.math.BigDecimal;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Exact integer reward draw: a chance of {@code c} percent (scale 4) wins when a uniform draw from
 * {@code [0, 1_000_000)} is below {@code c × 10_000}. 0 % never wins, 100 % always wins.
 */
@Component
public class RewardDraw {

    static final long DRAW_RANGE = 1_000_000L;

    private final RandomGenerator random;

    public RewardDraw(RandomGenerator random) {
        this.random = random;
    }

    /**
     * Draws once.
     *
     * @param chancePercentage win chance in percent, within [0, 100]
     * @return whether the draw wins
     * @throws IllegalArgumentException when the chance is outside [0, 100]
     */
    public boolean isWinning(BigDecimal chancePercentage) {
        if (chancePercentage.signum() < 0 || chancePercentage.compareTo(Money.HUNDRED) > 0) {
            throw new IllegalArgumentException("chancePercentage must be within [0, 100] but was "
                    + chancePercentage.toPlainString());
        }
        long threshold = chancePercentage.setScale(Money.PERCENT_SCALE, Money.ROUNDING)
                .movePointRight(Money.PERCENT_SCALE)
                .longValueExact();
        return random.nextLong(DRAW_RANGE) < threshold;
    }
}
