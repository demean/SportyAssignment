package com.sporty.jackpot.api.mapper;

import com.sporty.jackpot.api.dto.BetAcceptedResponse;
import com.sporty.jackpot.api.dto.BetEvaluationResponse;
import com.sporty.jackpot.api.dto.BetResponse;
import com.sporty.jackpot.api.dto.ContributionResponse;
import com.sporty.jackpot.api.dto.JackpotResponse;
import com.sporty.jackpot.api.dto.PolicyResponse;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps the domain model (the bets and read models the services return) to API response DTOs. The policy switches
 * are exhaustive over the sealed hierarchies, so a new policy type fails compilation until it is mapped here.
 */
@Component
public class ApiMapper {

    static final String ACCEPTED = "ACCEPTED";
    static final String FIXED = "FIXED";
    static final String VARIABLE = "VARIABLE";

    /** {@code acceptedAt} is the time the bet was accepted, i.e. its {@link Bet#placedAt()}. */
    public BetAcceptedResponse toAcceptedResponse(Bet bet) {
        return new BetAcceptedResponse(bet.betId(), bet.jackpotId(), ACCEPTED, bet.placedAt());
    }

    public BetResponse toResponse(ProcessedBet bet) {
        return new BetResponse(bet.betId(), bet.userId(), bet.jackpotId(), bet.betAmount(), bet.status().name(),
                bet.placedAt(), bet.processedAt());
    }

    public ContributionResponse toResponse(Contribution contribution) {
        return new ContributionResponse(contribution.betId(), contribution.userId(), contribution.jackpotId(),
                contribution.stakeAmount(), contribution.contributionAmount(), contribution.currentJackpotAmount(),
                contribution.createdAt());
    }

    public BetEvaluationResponse toResponse(BetEvaluation evaluation) {
        return new BetEvaluationResponse(evaluation.betId(), evaluation.userId(), evaluation.jackpotId(),
                evaluation.outcome().name(), evaluation.won(), evaluation.rewardAmount(),
                evaluation.winChancePercentage(), evaluation.evaluatedAt());
    }

    public JackpotResponse toResponse(Jackpot jackpot) {
        return new JackpotResponse(jackpot.id(), jackpot.name(), jackpot.initialPoolAmount(),
                jackpot.currentPoolAmount(), jackpot.cycle(), toPolicyResponse(jackpot.contributionPolicy()),
                toPolicyResponse(jackpot.rewardPolicy()), jackpot.updatedAt());
    }

    public PolicyResponse toPolicyResponse(ContributionPolicy policy) {
        return switch (policy) {
            case FixedContributionPolicy fixed -> new PolicyResponse(FIXED,
                    parameters(Map.entry("percentage", fixed.percentage())));
            case VariableContributionPolicy variable -> new PolicyResponse(VARIABLE, parameters(
                    Map.entry("startPercentage", variable.startPercentage()),
                    Map.entry("minPercentage", variable.minPercentage()),
                    Map.entry("decayPercentage", variable.decayPercentage()),
                    Map.entry("poolIncreaseStep", variable.poolIncreaseStep())));
        };
    }

    public PolicyResponse toPolicyResponse(RewardPolicy policy) {
        return switch (policy) {
            case FixedChanceRewardPolicy fixed -> new PolicyResponse(FIXED,
                    parameters(Map.entry("chancePercentage", fixed.chancePercentage())));
            case VariableChanceRewardPolicy variable -> new PolicyResponse(VARIABLE, parameters(
                    Map.entry("startChancePercentage", variable.startChancePercentage()),
                    Map.entry("chanceIncreasePercentage", variable.chanceIncreasePercentage()),
                    Map.entry("poolIncreaseStep", variable.poolIncreaseStep()),
                    Map.entry("poolLimit", variable.poolLimit())));
        };
    }

    @SafeVarargs
    private static Map<String, BigDecimal> parameters(Map.Entry<String, BigDecimal>... entries) {
        Map<String, BigDecimal> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : entries) {
            parameters.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(parameters);
    }
}
