package com.sporty.jackpot.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Business exceptions")
class JackpotServiceExceptionTest {

    static Stream<Arguments> exceptions() {
        return Stream.of(
                Arguments.of(new InvalidBetException("amount must be positive but was 0"), ErrorCode.INVALID_BET,
                        "amount must be positive but was 0"),
                Arguments.of(new BetNotFoundException("bet-1"), ErrorCode.BET_NOT_FOUND,
                        "Bet 'bet-1' was not found (unknown or not processed yet)"),
                Arguments.of(new BetNotContributingException("bet-2"), ErrorCode.BET_NOT_CONTRIBUTING,
                        "Bet 'bet-2' was processed but did not contribute to a jackpot (no matching jackpot)"),
                Arguments.of(new JackpotNotFoundException("jackpot-x"), ErrorCode.JACKPOT_NOT_FOUND,
                        "Jackpot 'jackpot-x' was not found"),
                Arguments.of(new JackpotConfigurationException("poolLimit must be > 0 but was 0"),
                        ErrorCode.INTERNAL_ERROR, "poolLimit must be > 0 but was 0"),
                Arguments.of(new BetPublishingException("bet-3", new TimeoutException("no ack")),
                        ErrorCode.BET_PUBLISH_FAILED,
                        "Bet 'bet-3' could not be confirmed by the message broker; outcome unknown - retry with the same betId"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exceptions")
    @DisplayName("carry their error code and a client-facing message")
    void carryErrorCodeAndMessage(JackpotServiceException exception, ErrorCode expectedCode, String expectedMessage) {
        assertThat(exception).isInstanceOf(RuntimeException.class).hasMessage(expectedMessage);
        assertThat(exception.getErrorCode()).isEqualTo(expectedCode);
    }

    @Test
    @DisplayName("BetPublishingException keeps the bet id (for the problem response) and the broker cause")
    void betPublishingException() {
        TimeoutException cause = new TimeoutException("no ack within 12s");

        BetPublishingException exception = new BetPublishingException("bet-42", cause);

        assertThat(exception.getBetId()).isEqualTo("bet-42");
        assertThat(exception).hasCause(cause).hasMessageContaining("retry with the same betId");
    }

    @Test
    @DisplayName("JackpotConfigurationException keeps the parsing cause")
    void jackpotConfigurationExceptionWithCause() {
        IllegalArgumentException cause = new IllegalArgumentException("unknown type");

        JackpotConfigurationException exception =
                new JackpotConfigurationException("Invalid contribution policy JSON: {}", cause);

        assertThat(exception).hasMessage("Invalid contribution policy JSON: {}").hasCause(cause);
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("exceptions without a cause have none")
    void noCause() {
        assertThat(new InvalidBetException("x")).hasNoCause();
        assertThat(new JackpotConfigurationException("x")).hasNoCause();
        assertThat(new BetNotFoundException("b")).hasNoCause();
    }
}
