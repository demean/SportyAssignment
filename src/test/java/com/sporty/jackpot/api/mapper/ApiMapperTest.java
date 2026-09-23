package com.sporty.jackpot.api.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.sporty.jackpot.api.dto.BetAcceptedResponse;
import com.sporty.jackpot.api.dto.BetEvaluationResponse;
import com.sporty.jackpot.api.dto.BetResponse;
import com.sporty.jackpot.api.dto.ContributionResponse;
import com.sporty.jackpot.api.dto.JackpotResponse;
import com.sporty.jackpot.api.dto.PolicyResponse;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ApiMapper")
class ApiMapperTest {

    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:30.123456Z");
    private static final Instant LATER = Instant.parse("2026-09-23T10:15:31.000001Z");

    private static final FixedContributionPolicy FIXED_CONTRIBUTION = new FixedContributionPolicy(new BigDecimal("5.0"));
    private static final VariableContributionPolicy VARIABLE_CONTRIBUTION = new VariableContributionPolicy(
            new BigDecimal("20.0"), new BigDecimal("5.0"), new BigDecimal("1.0"), new BigDecimal("100"));
    private static final FixedChanceRewardPolicy FIXED_CHANCE = new FixedChanceRewardPolicy(new BigDecimal("1.0"));
    private static final VariableChanceRewardPolicy VARIABLE_CHANCE = new VariableChanceRewardPolicy(
            new BigDecimal("5.0"), new BigDecimal("10.0"), new BigDecimal("10"), new BigDecimal("150"));

    private final ApiMapper mapper = new ApiMapper();

    @Nested
    @DisplayName("acknowledgements")
    class Acknowledgements {

        @Test
        @DisplayName("toAcceptedResponse reports status ACCEPTED with the bet's placedAt as acceptedAt")
        void mapsAcceptedResponse() {
            Bet bet = new Bet("bet-1", "user-1", "jackpot-1", new BigDecimal("10.00"), PLACED_AT);

            BetAcceptedResponse response = mapper.toAcceptedResponse(bet);

            assertThat(response).isEqualTo(new BetAcceptedResponse("bet-1", "jackpot-1", "ACCEPTED", PLACED_AT));
        }
    }

    @Nested
    @DisplayName("read models")
    class ReadModels {

        @ParameterizedTest
        @EnumSource(BetStatus.class)
        @DisplayName("toResponse(ProcessedBet) copies every field and exposes the status by name")
        void mapsProcessedBet(BetStatus status) {
            ProcessedBet bet = new ProcessedBet("bet-1", "user-1", "jackpot-1", new BigDecimal("250.00"), status,
                    PLACED_AT, LATER);

            BetResponse response = mapper.toResponse(bet);

            assertThat(response.betId()).isEqualTo("bet-1");
            assertThat(response.userId()).isEqualTo("user-1");
            assertThat(response.jackpotId()).isEqualTo("jackpot-1");
            assertThat(response.betAmount()).isEqualByComparingTo("250.00");
            assertThat(response.status()).isEqualTo(status.name());
            assertThat(response.placedAt()).isEqualTo(PLACED_AT);
            assertThat(response.processedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("toResponse(Contribution) copies every exposed field (the cycle stays internal)")
        void mapsContribution() {
            Contribution contribution = new Contribution("bet-1", "user-1", "jackpot-1", new BigDecimal("250.00"),
                    new BigDecimal("50.00"), new BigDecimal("150.00"), 3L, LATER);

            ContributionResponse response = mapper.toResponse(contribution);

            assertThat(response.betId()).isEqualTo("bet-1");
            assertThat(response.userId()).isEqualTo("user-1");
            assertThat(response.jackpotId()).isEqualTo("jackpot-1");
            assertThat(response.stakeAmount()).isEqualByComparingTo("250.00");
            assertThat(response.contributionAmount()).isEqualByComparingTo("50.00");
            assertThat(response.currentJackpotAmount()).isEqualByComparingTo("150.00");
            assertThat(response.createdAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("toResponse(BetEvaluation) of a won bet reports won=true with the reward")
        void mapsWonEvaluation() {
            BetEvaluation evaluation = new BetEvaluation("bet-1", "user-1", "jackpot-1", EvaluationOutcome.WON,
                    new BigDecimal("100.0000"), new BigDecimal("150.00"), 1L, LATER);

            BetEvaluationResponse response = mapper.toResponse(evaluation);

            assertThat(response.betId()).isEqualTo("bet-1");
            assertThat(response.userId()).isEqualTo("user-1");
            assertThat(response.jackpotId()).isEqualTo("jackpot-1");
            assertThat(response.outcome()).isEqualTo("WON");
            assertThat(response.won()).isTrue();
            assertThat(response.rewardAmount()).isEqualByComparingTo("150.00");
            assertThat(response.winChancePercentage()).isEqualByComparingTo("100.0000");
            assertThat(response.evaluatedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("toResponse(BetEvaluation) of a lost bet reports won=false and a zero reward")
        void mapsLostEvaluation() {
            BetEvaluation evaluation = new BetEvaluation("bet-2", "user-2", "jackpot-2", EvaluationOutcome.LOST,
                    new BigDecimal("0.0000"), new BigDecimal("0.00"), 2L, LATER);

            BetEvaluationResponse response = mapper.toResponse(evaluation);

            assertThat(response.outcome()).isEqualTo("LOST");
            assertThat(response.won()).isFalse();
            assertThat(response.rewardAmount()).isEqualByComparingTo("0.00");
            assertThat(response.winChancePercentage()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("toResponse(Jackpot) copies every field and maps both policies")
        void mapsJackpot() {
            Jackpot jackpot = new Jackpot("jackpot-lucky", "Lucky Demo", new BigDecimal("100.00"),
                    new BigDecimal("137.50"), 4L, VARIABLE_CONTRIBUTION, FIXED_CHANCE, LATER);

            JackpotResponse response = mapper.toResponse(jackpot);

            assertThat(response.id()).isEqualTo("jackpot-lucky");
            assertThat(response.name()).isEqualTo("Lucky Demo");
            assertThat(response.initialPoolAmount()).isEqualByComparingTo("100.00");
            assertThat(response.currentPoolAmount()).isEqualByComparingTo("137.50");
            assertThat(response.cycle()).isEqualTo(4L);
            assertThat(response.contributionPolicy()).isEqualTo(mapper.toPolicyResponse(VARIABLE_CONTRIBUTION));
            assertThat(response.contributionPolicy().type()).isEqualTo("VARIABLE");
            assertThat(response.rewardPolicy()).isEqualTo(mapper.toPolicyResponse(FIXED_CHANCE));
            assertThat(response.rewardPolicy().type()).isEqualTo("FIXED");
            assertThat(response.updatedAt()).isEqualTo(LATER);
        }
    }

    @Nested
    @DisplayName("policies")
    class Policies {

        static Stream<Arguments> contributionPolicies() {
            return Stream.of(
                    arguments(named("fixed", FIXED_CONTRIBUTION), "FIXED",
                            List.of(entry("percentage", "5.0"))),
                    arguments(named("variable", VARIABLE_CONTRIBUTION), "VARIABLE",
                            List.of(entry("startPercentage", "20.0"), entry("minPercentage", "5.0"),
                                    entry("decayPercentage", "1.0"), entry("poolIncreaseStep", "100"))));
        }

        static Stream<Arguments> rewardPolicies() {
            return Stream.of(
                    arguments(named("fixed chance", FIXED_CHANCE), "FIXED",
                            List.of(entry("chancePercentage", "1.0"))),
                    arguments(named("variable chance", VARIABLE_CHANCE), "VARIABLE",
                            List.of(entry("startChancePercentage", "5.0"), entry("chanceIncreasePercentage", "10.0"),
                                    entry("poolIncreaseStep", "10"), entry("poolLimit", "150"))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("contributionPolicies")
        @DisplayName("contribution policies map to their type and ordered parameters")
        void mapsContributionPolicy(ContributionPolicy policy, String type,
                                    List<Map.Entry<String, String>> parameters) {
            assertPolicy(mapper.toPolicyResponse(policy), type, parameters);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("rewardPolicies")
        @DisplayName("reward policies map to their type and ordered parameters")
        void mapsRewardPolicy(RewardPolicy policy, String type, List<Map.Entry<String, String>> parameters) {
            assertPolicy(mapper.toPolicyResponse(policy), type, parameters);
        }

        @Test
        @DisplayName("every permitted policy type has a mapping case above (sealed hierarchies)")
        void coversEveryPermittedPolicyType() {
            assertThat(Arrays.asList(ContributionPolicy.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(FixedContributionPolicy.class, VariableContributionPolicy.class);
            assertThat(Arrays.asList(RewardPolicy.class.getPermittedSubclasses()))
                    .containsExactlyInAnyOrder(FixedChanceRewardPolicy.class, VariableChanceRewardPolicy.class);
        }

        @Test
        @DisplayName("the parameters map is read-only")
        void parametersAreUnmodifiable() {
            Map<String, BigDecimal> parameters = mapper.toPolicyResponse(FIXED_CHANCE).parameters();

            assertThatThrownBy(() -> parameters.put("chancePercentage", BigDecimal.TEN))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        private static void assertPolicy(PolicyResponse response, String type,
                                         List<Map.Entry<String, String>> expectedParameters) {
            assertThat(response.type()).isEqualTo(type);
            assertThat(response.parameters().keySet())
                    .containsExactlyElementsOf(expectedParameters.stream().map(Map.Entry::getKey).toList());
            expectedParameters.forEach(expected -> assertThat(response.parameters().get(expected.getKey()))
                    .as(expected.getKey())
                    .isEqualByComparingTo(expected.getValue()));
        }
    }
}
