package com.sporty.jackpot.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.InvalidBetException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("BetEventMapper")
class BetEventMapperTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:30.123456Z");

    private final BetEventMapper mapper = new BetEventMapper();

    @Test
    @DisplayName("toEvent copies every field of the bet into the Kafka payload")
    void toEventCopiesAllFields() {
        Bet bet = new Bet("bet-1", "user-1", "jackpot-1", new BigDecimal("10.5"), PLACED_AT);

        BetPlacedEvent event = mapper.toEvent(bet);

        assertThat(event.betId()).isEqualTo("bet-1");
        assertThat(event.userId()).isEqualTo("user-1");
        assertThat(event.jackpotId()).isEqualTo("jackpot-1");
        assertThat(event.betAmount()).isEqualByComparingTo("10.50");
        assertThat(event.betAmount().scale()).as("amount is published normalized to scale 2").isEqualTo(2);
        assertThat(event.placedAt()).isEqualTo(PLACED_AT);
    }

    @Test
    @DisplayName("toBet builds the domain bet and normalizes the amount to scale 2")
    void toBetMapsAndNormalizes() {
        BetPlacedEvent event = new BetPlacedEvent("bet-2", "user-2", "jackpot-2", new BigDecimal("7"), PLACED_AT);

        Bet bet = mapper.toBet(event);

        assertThat(bet.betId()).isEqualTo("bet-2");
        assertThat(bet.userId()).isEqualTo("user-2");
        assertThat(bet.jackpotId()).isEqualTo("jackpot-2");
        assertThat(bet.amount()).isEqualByComparingTo("7.00");
        assertThat(bet.amount().scale()).isEqualTo(2);
        assertThat(bet.placedAt()).isEqualTo(PLACED_AT);
    }

    @Test
    @DisplayName("a bet survives the round trip through its Kafka payload unchanged")
    void roundTrip() {
        Bet bet = new Bet("bet.3:x_y-z", "user-3", "jackpot-3", new BigDecimal("1000000000.00"), PLACED_AT);

        assertThat(mapper.toBet(mapper.toEvent(bet))).isEqualTo(bet);
    }

    static Stream<Arguments> invalidPayloads() {
        BigDecimal ten = new BigDecimal("10.00");
        return Stream.of(
                Arguments.of(new BetPlacedEvent(null, "user", "jackpot", ten, PLACED_AT), "betId"),
                Arguments.of(new BetPlacedEvent("bet", " ", "jackpot", ten, PLACED_AT), "userId"),
                Arguments.of(new BetPlacedEvent("bet", "user", "jack pot", ten, PLACED_AT), "jackpotId"),
                Arguments.of(new BetPlacedEvent("bet", "user", "jackpot", null, PLACED_AT), "amount must not be null"),
                Arguments.of(new BetPlacedEvent("bet", "user", "jackpot", BigDecimal.ZERO, PLACED_AT),
                        "amount must be positive"),
                Arguments.of(new BetPlacedEvent("bet", "user", "jackpot", new BigDecimal("-1.00"), PLACED_AT),
                        "amount must be positive"),
                Arguments.of(new BetPlacedEvent("bet", "user", "jackpot", new BigDecimal("1.001"), PLACED_AT),
                        "at most 2 decimals"),
                Arguments.of(new BetPlacedEvent("bet", "user", "jackpot", new BigDecimal("1000000000.01"),
                        PLACED_AT), "must not exceed"),
                Arguments.of(new BetPlacedEvent("bet", "user", "jackpot", ten, null), "placedAt"));
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("invalidPayloads")
    @DisplayName("toBet rejects a payload that violates a bet invariant with a non-retryable InvalidBetException")
    void toBetRejectsInvalidPayload(BetPlacedEvent event, String expectedMessagePart) {
        assertThatThrownBy(() -> mapper.toBet(event))
                .isInstanceOfSatisfying(InvalidBetException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_BET))
                .hasMessageContaining(expectedMessagePart);
    }
}
