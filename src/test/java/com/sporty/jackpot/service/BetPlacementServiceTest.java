package com.sporty.jackpot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.exception.BetPublishingException;
import com.sporty.jackpot.exception.InvalidBetException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@DisplayName("BetPlacementService")
class BetPlacementServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:15:30.123456Z");

    @Mock
    private BetEventPublisher publisher;

    private BetPlacementService service;

    @BeforeEach
    void setUp() {
        service = new BetPlacementService(publisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("stamps the bet with the clock, publishes it through the port and returns the published bet")
    void placesAndPublishesTheBet() {
        Bet placed = service.place("bet-1", "user-1", "jackpot-1", new BigDecimal("12.5"));

        Bet expected = new Bet("bet-1", "user-1", "jackpot-1", new BigDecimal("12.50"), NOW);
        assertThat(placed).isEqualTo(expected);
        assertThat(placed.placedAt()).as("accepted at the clock's instant").isEqualTo(NOW);
        assertThat(placed.amount()).as("normalized to scale 2").hasScaleOf(2);
        verify(publisher).publish(expected);
    }

    @ParameterizedTest(name = "betId={0}, amount={1}")
    @CsvSource({
            "'', 10.00",
            "..,  10.00",
            "bet-1, 0.001",
            "bet-1, 0",
            "bet-1, 1000000000.01"
    })
    @DisplayName("enforces the domain invariants itself (a caller may bypass bean validation): nothing is published")
    void rejectsAnInvalidBetWithoutPublishing(String betId, BigDecimal amount) {
        assertThatThrownBy(() -> service.place(betId, "user-1", "jackpot-1", amount))
                .isInstanceOf(InvalidBetException.class);

        verifyNoInteractions(publisher);
    }

    @Test
    @DisplayName("propagates an unconfirmed publication (outcome unknown) to the caller")
    void propagatesPublishingFailure() {
        BetPublishingException failure = new BetPublishingException("bet-1", new TimeoutException("no ack"));
        doThrow(failure).when(publisher).publish(any(Bet.class));

        assertThatThrownBy(() -> service.place("bet-1", "user-1", "jackpot-1", BigDecimal.TEN)).isSameAs(failure);
    }

    @Test
    @DisplayName("owns no repository and is not transactional: publishing never touches the database")
    void isNotTransactional() {
        assertThat(BetPlacementService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(BetPlacementService.class.getDeclaredFields())
                .extracting(field -> field.getType().getPackageName())
                .doesNotContain("com.sporty.jackpot.persistence.repository");
    }
}
