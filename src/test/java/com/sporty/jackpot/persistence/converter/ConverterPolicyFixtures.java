package com.sporty.jackpot.persistence.converter;

import static org.assertj.core.api.Assertions.fail;

import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

/**
 * One representative instance and the JSON type id of every permitted policy subclass. Tests iterate over
 * {@code getPermittedSubclasses()}, so a new policy record fails them until it gets a sample here and a registered
 * JSON subtype in {@link PolicyJson}.
 */
final class ConverterPolicyFixtures {

    record Sample<T>(T policy, String typeId) {
    }

    private static final Map<Class<?>, Sample<?>> SAMPLES = Map.of(
            FixedContributionPolicy.class,
            new Sample<>(new FixedContributionPolicy(new BigDecimal("5.25")), "FIXED"),
            VariableContributionPolicy.class,
            new Sample<>(new VariableContributionPolicy(new BigDecimal("10.0"), new BigDecimal("1.5"),
                    new BigDecimal("0.25"), new BigDecimal("1000.00")), "VARIABLE"),
            FixedChanceRewardPolicy.class,
            new Sample<>(new FixedChanceRewardPolicy(new BigDecimal("0.0001")), "FIXED"),
            VariableChanceRewardPolicy.class,
            new Sample<>(new VariableChanceRewardPolicy(new BigDecimal("0.10"), new BigDecimal("0.5"),
                    new BigDecimal("1000"), new BigDecimal("25000.00")), "VARIABLE"));

    private ConverterPolicyFixtures() {
    }

    static Stream<Class<?>> contributionPolicyTypes() {
        return Arrays.stream(ContributionPolicy.class.getPermittedSubclasses());
    }

    static Stream<Class<?>> rewardPolicyTypes() {
        return Arrays.stream(RewardPolicy.class.getPermittedSubclasses());
    }

    static Stream<Class<?>> allPolicyTypes() {
        return Stream.concat(contributionPolicyTypes(), rewardPolicyTypes());
    }

    static Sample<?> sample(Class<?> policyType) {
        Sample<?> sample = SAMPLES.get(policyType);
        if (sample == null) {
            fail("No sample for policy type %s: add one to ConverterPolicyFixtures", policyType.getName());
        }
        return sample;
    }

    static ContributionPolicy contributionSample(Class<?> policyType) {
        return (ContributionPolicy) sample(policyType).policy();
    }

    static RewardPolicy rewardSample(Class<?> policyType) {
        return (RewardPolicy) sample(policyType).policy();
    }
}
