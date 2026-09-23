package com.sporty.jackpot.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.InvalidBetException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Bet invariants")
class BetTest {

    private static final Instant PLACED_AT = Instant.parse("2026-01-01T10:15:30.123456Z");
    private static final String MAX_LENGTH_ID = "a".repeat(64);
    private static final String TOO_LONG_ID = "a".repeat(65);

    private static Bet bet(String amount) {
        return new Bet("bet-1", "user-1", "jackpot-1", new BigDecimal(amount), PLACED_AT);
    }

    @Test
    @DisplayName("a valid bet keeps its values and normalizes the amount to scale 2")
    void validBet() {
        Bet bet = new Bet("bet-1", "user-1", "jackpot-1", new BigDecimal("10.5"), PLACED_AT);

        assertThat(bet.betId()).isEqualTo("bet-1");
        assertThat(bet.userId()).isEqualTo("user-1");
        assertThat(bet.jackpotId()).isEqualTo("jackpot-1");
        assertThat(bet.amount()).isEqualTo(new BigDecimal("10.50"));
        assertThat(bet.placedAt()).isEqualTo(PLACED_AT);
    }

    @Test
    void constants() {
        assertThat(Bet.ID_REGEX).isEqualTo("^(?!\\.{1,2}$)[A-Za-z0-9._:-]{1,64}$");
        assertThat(Bet.MAX_AMOUNT).isEqualByComparingTo("1000000000.00");
    }

    @Nested
    @DisplayName("ids")
    class Ids {

        static Stream<Arguments> idFields() {
            return Stream.of(
                    Arguments.of("betId", (Function<String, Bet>) id ->
                            new Bet(id, "user-1", "jackpot-1", BigDecimal.TEN, PLACED_AT)),
                    Arguments.of("userId", (Function<String, Bet>) id ->
                            new Bet("bet-1", id, "jackpot-1", BigDecimal.TEN, PLACED_AT)),
                    Arguments.of("jackpotId", (Function<String, Bet>) id ->
                            new Bet("bet-1", "user-1", id, BigDecimal.TEN, PLACED_AT)));
        }

        static Stream<Arguments> invalidIds() {
            return idFields().flatMap(field -> Stream.of(
                            null, "", " ", "   ", "bet 1", " bet", "bet ", "bet/1", "bet#1", "bet\n", "bét", "bet;1",
                            // URL dot segments: /api/v1/bets/.. would resolve to another resource
                            ".", "..",
                            TOO_LONG_ID)
                    .map(id -> Arguments.of(field.get()[0], field.get()[1], id)));
        }

        static Stream<Arguments> validIds() {
            return idFields().flatMap(field -> Stream.of("a", "Z", "0", "a.b_c:d-e", "BET-2026:01.x_y", MAX_LENGTH_ID,
                            // dots are fine unless the whole id is a dot segment
                            "...", ".a", "a.", "..a", "a..")
                    .map(id -> Arguments.of(field.get()[0], field.get()[1], id)));
        }

        @ParameterizedTest(name = "{0} = [{2}] is rejected")
        @MethodSource("invalidIds")
        void rejectsIdsNotMatchingTheRegex(String field, Function<String, Bet> factory, String id) {
            assertThatThrownBy(() -> factory.apply(id))
                    .isInstanceOf(InvalidBetException.class)
                    .hasMessageStartingWith(field + " must match " + Bet.ID_REGEX)
                    .hasMessageEndingWith("but was '" + id + "'")
                    .extracting(e -> ((InvalidBetException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_BET);
        }

        @ParameterizedTest(name = "{0} = [{2}] is accepted")
        @MethodSource("validIds")
        void acceptsIdsMatchingTheRegex(String field, Function<String, Bet> factory, String id) {
            Bet bet = factory.apply(id);

            String actual = switch (field) {
                case "betId" -> bet.betId();
                case "userId" -> bet.userId();
                default -> bet.jackpotId();
            };
            assertThat(actual).isEqualTo(id);
        }

        @ParameterizedTest(name = "{0} characters")
        @ValueSource(ints = {81, 350_000})
        @DisplayName("a rejected id longer than 80 characters is quoted cut to 80 characters plus its length")
        void longRejectedIdIsQuotedCut(int length) {
            String id = "!".repeat(length);

            assertThatThrownBy(() -> new Bet(id, "u", "j", BigDecimal.ONE, PLACED_AT))
                    .isInstanceOf(InvalidBetException.class)
                    .hasMessage("betId must match " + Bet.ID_REGEX + " but was '" + "!".repeat(80) + "...' ("
                            + length + " characters)");
        }

        @Test
        @DisplayName("an id of exactly 80 characters is quoted in full")
        void rejectedIdOfEightyCharactersIsQuotedInFull() {
            String id = "!".repeat(80);

            assertThatThrownBy(() -> new Bet(id, "u", "j", BigDecimal.ONE, PLACED_AT))
                    .hasMessageEndingWith("but was '" + id + "'");
        }

        @Test
        @DisplayName("64 characters is the maximum id length, 65 is rejected")
        void idLengthBoundary() {
            assertThat(new Bet(MAX_LENGTH_ID, "u", "j", BigDecimal.ONE, PLACED_AT).betId()).hasSize(64);
            assertThatThrownBy(() -> new Bet(TOO_LONG_ID, "u", "j", BigDecimal.ONE, PLACED_AT))
                    .isInstanceOf(InvalidBetException.class)
                    .hasMessageStartingWith("betId must match");
        }
    }

    @Nested
    @DisplayName("amount")
    class Amount {

        @ParameterizedTest
        @NullSource
        void rejectsNull(BigDecimal amount) {
            assertThatThrownBy(() -> new Bet("bet-1", "user-1", "jackpot-1", amount, PLACED_AT))
                    .isInstanceOf(InvalidBetException.class)
                    .hasMessage("amount must not be null");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"0", "0.00", "-0.01", "-100", "-1000000000.01"})
        void rejectsZeroAndNegativeAmounts(String amount) {
            assertThatThrownBy(() -> bet(amount))
                    .isInstanceOf(InvalidBetException.class)
                    .hasMessage("amount must be positive but was " + new BigDecimal(amount));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"0.001", "10.005", "10.123", "1000000000.001", "0.0000001"})
        void rejectsMoreThanTwoSignificantDecimals(String amount) {
            assertThatThrownBy(() -> bet(amount))
                    .isInstanceOf(InvalidBetException.class)
                    .hasMessage("amount must have at most 2 decimals but was " + new BigDecimal(amount));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1000000000.01", "1000000001", "1E+10", "99999999999999999999.99"})
        void rejectsAmountsAboveTheMaximum(String amount) {
            assertThatThrownBy(() -> bet(amount))
                    .isInstanceOf(InvalidBetException.class)
                    .hasMessage("amount must not exceed 1000000000.00 but was " + new BigDecimal(amount));
        }

        /**
         * Tiny JSON, gigantic plain form: quoting such an amount with toPlainString() built a string of a billion
         * characters (OutOfMemoryError in the Kafka consumer). The message uses scientific notation instead.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "1e999999999, amount must not exceed 1000000000.00 but was 1E+999999999",
                "-1e999999999, amount must be positive but was -1E+999999999",
                "1e-999999999, amount must have at most 2 decimals but was 1E-999999999",
                "1e-3000000, amount must have at most 2 decimals but was 1E-3000000"
        })
        @DisplayName("an amount with an extreme exponent is rejected with a short message")
        void rejectsExtremeExponentsWithAShortMessage(String amount, String expectedMessage) {
            assertThatThrownBy(() -> bet(amount))
                    .isInstanceOf(InvalidBetException.class)
                    .hasMessage(expectedMessage);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "0.01, 0.01",
                "1, 1.00",
                "10.5, 10.50",
                // trailing zeros beyond scale 2 are not extra precision
                "10.010, 10.01",
                "10.50000, 10.50",
                "1E+3, 1000.00",
                "999999999.99, 999999999.99",
                "1000000000.00, 1000000000.00",
                "1000000000, 1000000000.00"
        })
        void acceptsAndNormalizesValidAmounts(String amount, String expected) {
            BigDecimal normalized = bet(amount).amount();

            assertThat(normalized).isEqualTo(new BigDecimal(expected));
            assertThat(normalized.scale()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("placedAt is required")
    void rejectsNullPlacedAt() {
        assertThatThrownBy(() -> new Bet("bet-1", "user-1", "jackpot-1", BigDecimal.TEN, null))
                .isInstanceOf(InvalidBetException.class)
                .hasMessage("placedAt must not be null");
    }

    @Test
    @DisplayName("ids are validated before the amount, the amount before placedAt")
    void reportsTheFirstViolation() {
        assertThatThrownBy(() -> new Bet(null, null, null, null, null)).hasMessageStartingWith("betId");
        assertThatThrownBy(() -> new Bet("b", "u", "j", null, null)).hasMessageStartingWith("amount");
    }
}
