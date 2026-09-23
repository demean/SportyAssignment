package com.sporty.jackpot.domain.model;

import com.sporty.jackpot.domain.Money;
import com.sporty.jackpot.exception.InvalidBetException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A bet placed by a user on a jackpot: the command that flows from the API through Kafka into processing.
 * All invariants are enforced on construction; the amount is normalized to scale 2.
 *
 * @param betId     globally unique bet id (idempotency key)
 * @param userId    id of the betting user
 * @param jackpotId id of the jackpot the bet contributes to
 * @param amount    stake, {@code 0 < amount <= }{@link #MAX_AMOUNT}, at most 2 decimals
 * @param placedAt  when the bet was accepted by the API
 */
public record Bet(String betId, String userId, String jackpotId, BigDecimal amount, Instant placedAt) {

    /** Allowed format of bet, user and jackpot ids. */
    public static final String ID_REGEX = "^[A-Za-z0-9._:-]{1,64}$";

    /** Largest accepted stake. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000.00");

    private static final Pattern ID_PATTERN = Pattern.compile(ID_REGEX);

    public Bet {
        requireValidId("betId", betId);
        requireValidId("userId", userId);
        requireValidId("jackpotId", jackpotId);
        amount = requireValidAmount(amount);
        if (placedAt == null) {
            throw new InvalidBetException("placedAt must not be null");
        }
    }

    private static void requireValidId(String name, String value) {
        if (value == null || !ID_PATTERN.matcher(value).matches()) {
            throw new InvalidBetException(name + " must match " + ID_REGEX + " but was '" + value + "'");
        }
    }

    private static BigDecimal requireValidAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidBetException("amount must not be null");
        }
        if (amount.signum() <= 0) {
            throw new InvalidBetException("amount must be positive but was " + amount.toPlainString());
        }
        if (amount.stripTrailingZeros().scale() > Money.SCALE) {
            throw new InvalidBetException("amount must have at most " + Money.SCALE + " decimals but was "
                    + amount.toPlainString());
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidBetException("amount must not exceed " + MAX_AMOUNT.toPlainString() + " but was "
                    + amount.toPlainString());
        }
        return Money.normalize(amount);
    }
}
