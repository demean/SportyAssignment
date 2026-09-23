package com.sporty.jackpot.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Money and percentage arithmetic conventions: amounts have scale 2, percentages scale 4, both rounded
 * {@link RoundingMode#HALF_EVEN}. Values are compared with {@code compareTo}, never {@code equals}.
 */
public final class Money {

    public static final int SCALE = 2;
    public static final int PERCENT_SCALE = 4;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    public static final BigDecimal ZERO_PERCENT = BigDecimal.ZERO.setScale(PERCENT_SCALE);

    private Money() {
    }

    /**
     * Normalizes an amount to scale 2.
     *
     * @param amount the amount, never {@code null}
     * @return the amount with scale {@value #SCALE}
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return Objects.requireNonNull(amount, "amount").setScale(SCALE, ROUNDING);
    }

    /**
     * Computes {@code amount × percentage / 100}, normalized to scale 2.
     *
     * @param amount     the base amount
     * @param percentage the percentage in [0, 100]
     * @return the rounded share of the amount
     */
    public static BigDecimal percentageOf(BigDecimal amount, BigDecimal percentage) {
        return normalize(amount.multiply(percentage).movePointLeft(2));
    }

    /**
     * Normalizes a percentage to scale 4.
     *
     * @param percentage the percentage, never {@code null}
     * @return the percentage with scale {@value #PERCENT_SCALE}
     */
    public static BigDecimal normalizePercentage(BigDecimal percentage) {
        return Objects.requireNonNull(percentage, "percentage").setScale(PERCENT_SCALE, ROUNDING);
    }
}
