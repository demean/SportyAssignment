package com.sporty.jackpot.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessedBet")
class ProcessedBetTest {

    private static final Instant PLACED_AT = Instant.parse("2026-03-01T12:00:00.000001Z");
    private static final Instant PROCESSED_AT = PLACED_AT.plusMillis(15);

    @Test
    @DisplayName("is a value object: equal field values mean equal read models")
    void valueSemantics() {
        ProcessedBet bet = new ProcessedBet("bet-1", "user-1", "unknown-jackpot", new BigDecimal("12.50"),
                BetStatus.NO_MATCHING_JACKPOT, PLACED_AT, PROCESSED_AT);

        assertThat(bet).isEqualTo(new ProcessedBet("bet-1", "user-1", "unknown-jackpot", new BigDecimal("12.50"),
                BetStatus.NO_MATCHING_JACKPOT, PLACED_AT, PROCESSED_AT));
        assertThat(bet.status()).isEqualTo(BetStatus.NO_MATCHING_JACKPOT);
        assertThat(bet.processedAt()).isAfter(bet.placedAt());
    }

    @Test
    @DisplayName("amounts are compared with equals, so read models must carry normalized (scale 2) amounts")
    void bigDecimalScaleMatters() {
        ProcessedBet scale2 = new ProcessedBet("bet-1", "u", "j", new BigDecimal("12.50"), BetStatus.CONTRIBUTED,
                PLACED_AT, PROCESSED_AT);
        ProcessedBet scale1 = new ProcessedBet("bet-1", "u", "j", new BigDecimal("12.5"), BetStatus.CONTRIBUTED,
                PLACED_AT, PROCESSED_AT);

        assertThat(scale2).isNotEqualTo(scale1);
        assertThat(scale2.betAmount()).isEqualByComparingTo(scale1.betAmount());
    }

    @Test
    @DisplayName("statuses are exactly CONTRIBUTED and NO_MATCHING_JACKPOT (persisted as strings, DB CHECK constraint)")
    void statuses() {
        assertThat(BetStatus.values()).extracting(Enum::name).containsExactly("CONTRIBUTED", "NO_MATCHING_JACKPOT");
    }
}
