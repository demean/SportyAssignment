package com.sporty.jackpot.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessingResult factories")
class ProcessingResultTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00.000001Z");

    @Test
    @DisplayName("processed: carries contribution and evaluation, bet id taken from the contribution")
    void processed() {
        Contribution contribution = new Contribution("bet-7", "user-1", "jackpot-lucky", new BigDecimal("250.00"),
                new BigDecimal("50.00"), new BigDecimal("150.00"), 1L, NOW);
        BetEvaluation evaluation = new BetEvaluation("bet-7", "user-1", "jackpot-lucky", EvaluationOutcome.WON,
                new BigDecimal("100.0000"), new BigDecimal("150.00"), 1L, NOW);

        ProcessingResult result = ProcessingResult.processed(contribution, evaluation);

        assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(result.betId()).isEqualTo("bet-7");
        assertThat(result.contribution()).isSameAs(contribution);
        assertThat(result.evaluation()).isSameAs(evaluation);
        assertThat(result.contribution().stakeAmount()).isEqualByComparingTo("250.00");
        assertThat(result.contribution().contributionAmount()).isEqualByComparingTo("50.00");
        assertThat(result.contribution().currentJackpotAmount()).isEqualByComparingTo("150.00");
        assertThat(result.contribution().jackpotCycle()).isEqualTo(1L);
        assertThat(result.contribution().createdAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("duplicate: no contribution, no evaluation")
    void duplicate() {
        ProcessingResult result = ProcessingResult.duplicate("bet-8");

        assertThat(result).isEqualTo(new ProcessingResult(ProcessingStatus.DUPLICATE, "bet-8", null, null));
    }

    @Test
    @DisplayName("no matching jackpot: no contribution, no evaluation")
    void noMatchingJackpot() {
        ProcessingResult result = ProcessingResult.noMatchingJackpot("bet-9");

        assertThat(result.status()).isEqualTo(ProcessingStatus.NO_MATCHING_JACKPOT);
        assertThat(result.betId()).isEqualTo("bet-9");
        assertThat(result.contribution()).isNull();
        assertThat(result.evaluation()).isNull();
    }

    @Test
    @DisplayName("processing statuses are exactly PROCESSED, DUPLICATE, NO_MATCHING_JACKPOT (metric tag values)")
    void processingStatuses() {
        assertThat(ProcessingStatus.values()).extracting(Enum::name)
                .containsExactly("PROCESSED", "DUPLICATE", "NO_MATCHING_JACKPOT");
    }
}
