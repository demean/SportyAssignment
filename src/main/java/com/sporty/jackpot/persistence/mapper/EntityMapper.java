package com.sporty.jackpot.persistence.mapper;

import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.persistence.entity.BetEntity;
import com.sporty.jackpot.persistence.entity.BetEvaluationEntity;
import com.sporty.jackpot.persistence.entity.JackpotContributionEntity;
import com.sporty.jackpot.persistence.entity.JackpotEntity;
import org.springframework.stereotype.Component;

/**
 * Maps entities to domain read models; entities never leave the service/persistence layers.
 */
@Component
public class EntityMapper {

    public ProcessedBet toProcessedBet(BetEntity entity) {
        return new ProcessedBet(entity.getId(), entity.getUserId(), entity.getJackpotId(), entity.getBetAmount(),
                entity.getStatus(), entity.getPlacedAt(), entity.getProcessedAt());
    }

    public Contribution toContribution(JackpotContributionEntity entity) {
        return new Contribution(entity.getBetId(), entity.getUserId(), entity.getJackpotId(), entity.getStakeAmount(),
                entity.getContributionAmount(), entity.getCurrentJackpotAmount(), entity.getPoolCycle(),
                entity.getCreatedAt());
    }

    public BetEvaluation toEvaluation(BetEvaluationEntity entity) {
        return new BetEvaluation(entity.getBetId(), entity.getUserId(), entity.getJackpotId(), entity.getOutcome(),
                entity.getWinChancePercentage(), entity.getRewardAmount(), entity.getPoolCycle(),
                entity.getCreatedAt());
    }

    public Jackpot toJackpot(JackpotEntity entity) {
        return new Jackpot(entity.getId(), entity.getName(), entity.getInitialPoolAmount(),
                entity.getCurrentPoolAmount(), entity.getCycle(), entity.getContributionPolicy(),
                entity.getRewardPolicy(), entity.getUpdatedAt());
    }
}
