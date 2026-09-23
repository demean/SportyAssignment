package com.sporty.jackpot.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.persistence.entity.BetEntity;
import com.sporty.jackpot.persistence.entity.BetEvaluationEntity;
import com.sporty.jackpot.persistence.entity.JackpotContributionEntity;
import com.sporty.jackpot.persistence.entity.JackpotEntity;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("EntityMapper")
class EntityMapperTest {

    private static final Instant T1 = Instant.parse("2026-04-01T12:00:00.000001Z");
    private static final Instant T2 = Instant.parse("2026-04-01T12:00:02.5Z");

    private final EntityMapper mapper = new EntityMapper();

    @ParameterizedTest
    @EnumSource(BetStatus.class)
    @DisplayName("toProcessedBet copies every column of the bet")
    void mapsBet(BetStatus status) {
        BetEntity entity = new BetEntity("bet-1", "user-1", "jackpot-1", new BigDecimal("25.00"), status, T1, T2);

        assertThat(mapper.toProcessedBet(entity)).isEqualTo(
                new ProcessedBet("bet-1", "user-1", "jackpot-1", new BigDecimal("25.00"), status, T1, T2));
    }

    @Test
    @DisplayName("toContribution copies every column of the contribution, the pool cycle becoming the jackpot cycle")
    void mapsContribution() {
        JackpotContributionEntity entity = new JackpotContributionEntity("bet-2", "user-2", "jackpot-2",
                new BigDecimal("250.00"), new BigDecimal("50.00"), new BigDecimal("150.00"), 4, T1);

        assertThat(mapper.toContribution(entity)).isEqualTo(new Contribution("bet-2", "user-2", "jackpot-2",
                new BigDecimal("250.00"), new BigDecimal("50.00"), new BigDecimal("150.00"), 4, T1));
    }

    @ParameterizedTest
    @EnumSource(EvaluationOutcome.class)
    @DisplayName("toEvaluation copies every column of the evaluation, created-at becoming evaluated-at")
    void mapsEvaluation(EvaluationOutcome outcome) {
        BigDecimal reward = outcome == EvaluationOutcome.WON ? new BigDecimal("150.00") : new BigDecimal("0.00");
        BetEvaluationEntity entity = new BetEvaluationEntity("bet-3", "user-3", "jackpot-3", outcome,
                new BigDecimal("100.0000"), reward, 2, T2);

        BetEvaluation evaluation = mapper.toEvaluation(entity);

        assertThat(evaluation).isEqualTo(new BetEvaluation("bet-3", "user-3", "jackpot-3", outcome,
                new BigDecimal("100.0000"), reward, 2, T2));
        assertThat(evaluation.won()).isEqualTo(outcome == EvaluationOutcome.WON);
    }

    @Test
    @DisplayName("toJackpot copies the jackpot state including both policies and the last pool change")
    void mapsJackpot() {
        FixedContributionPolicy contributionPolicy = new FixedContributionPolicy(new BigDecimal("5.0"));
        VariableChanceRewardPolicy rewardPolicy = new VariableChanceRewardPolicy(new BigDecimal("0.1"),
                new BigDecimal("0.5"), new BigDecimal("1000"), new BigDecimal("25000"));
        JackpotEntity entity = new JackpotEntity("jackpot-4", "Jackpot Four", new BigDecimal("5000.00"),
                new BigDecimal("5000.00"), contributionPolicy, rewardPolicy, 1, T1);
        entity.addContribution(new BigDecimal("12.34"), T2);

        assertThat(mapper.toJackpot(entity)).isEqualTo(new Jackpot("jackpot-4", "Jackpot Four",
                new BigDecimal("5000.00"), new BigDecimal("5012.34"), 1, contributionPolicy, rewardPolicy, T2));
    }
}
