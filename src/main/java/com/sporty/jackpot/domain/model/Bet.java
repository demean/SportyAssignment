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

    /**
     * Allowed format of bet, user and jackpot ids: 1 to 64 of {@code A-Z a-z 0-9 . _ : -}, but not {@code .} or
     * {@code ..}, which are URL dot segments (RFC 3986 section 5.2.4): HTTP clients would resolve
     * {@code /api/v1/bets/..} to another resource, so such a bet could never be looked up.
     */
    public static final String ID_REGEX = "^(?!\\.{1,2}$)[A-Za-z0-9._:-]{1,64}$";

    /** Largest accepted stake. */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000.00");

    /**
     * Longest prefix of a rejected id quoted in the error message. Ids are untrusted input of any length (a Kafka
     * record is not size-checked like a request body), and the message travels into logs and dead-letter headers.
     */
    static final int MAX_QUOTED_ID_LENGTH = 80;

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
            throw new InvalidBetException(name + " must match " + ID_REGEX + " but was " + quoted(value));
        }
    }

    /** {@code 'value'}, cut to its first {@value #MAX_QUOTED_ID_LENGTH} characters (plus its length) when longer. */
    private static String quoted(String value) {
        if (value == null || value.length() <= MAX_QUOTED_ID_LENGTH) {
            return "'" + value + "'";
        }
        return "'" + value.substring(0, MAX_QUOTED_ID_LENGTH) + "...' (" + value.length() + " characters)";
    }

    /**
     * Validates the stake and normalizes it to scale 2. A rejected amount is quoted with {@link BigDecimal#toString()},
     * never {@code toPlainString()}: an untrusted amount such as {@code 1e999999999} (11 characters of JSON) has a
     * plain form of a billion digits, while {@code toString()} switches to scientific notation.
     */
    private static BigDecimal requireValidAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidBetException("amount must not be null");
        }
        if (amount.signum() <= 0) {
            throw new InvalidBetException("amount must be positive but was " + amount);
        }
        if (amount.stripTrailingZeros().scale() > Money.SCALE) {
            throw new InvalidBetException("amount must have at most " + Money.SCALE + " decimals but was " + amount);
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidBetException("amount must not exceed " + MAX_AMOUNT.toPlainString() + " but was "
                    + amount);
        }
        return Money.normalize(amount);
    }
}
