package com.sporty.jackpot.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.model.BetStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BetEntity")
class BetEntityTest {

    @Test
    @DisplayName("a bet built by the business constructor is new, so saving it always INSERTs")
    void businessConstructorCreatesANewBet() {
        Instant placedAt = Instant.parse("2026-02-01T08:00:00Z");
        Instant processedAt = Instant.parse("2026-02-01T08:00:01Z");

        BetEntity bet = new BetEntity("bet-1", "user-1", "jackpot-1", new BigDecimal("12.50"),
                BetStatus.NO_MATCHING_JACKPOT, placedAt, processedAt);

        assertThat(bet.isNew()).isTrue();
        assertThat(bet.getId()).isEqualTo("bet-1");
        assertThat(bet.getUserId()).isEqualTo("user-1");
        assertThat(bet.getJackpotId()).isEqualTo("jackpot-1");
        assertThat(bet.getBetAmount()).isEqualByComparingTo("12.50");
        assertThat(bet.getStatus()).isEqualTo(BetStatus.NO_MATCHING_JACKPOT);
        assertThat(bet.getPlacedAt()).isEqualTo(placedAt);
        assertThat(bet.getProcessedAt()).isEqualTo(processedAt);
    }
}
