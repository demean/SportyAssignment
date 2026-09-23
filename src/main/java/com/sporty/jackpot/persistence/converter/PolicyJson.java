package com.sporty.jackpot.persistence.converter;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON representation of the policy hierarchies ({@code {"type":"FIXED",...}}), configured with mix-ins so the
 * domain stays annotation-free. Unknown properties are rejected so that typos in stored policies fail loudly.
 */
final class PolicyJson {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FixedContributionPolicy.class, name = "FIXED"),
            @JsonSubTypes.Type(value = VariableContributionPolicy.class, name = "VARIABLE")
    })
    private interface ContributionPolicyMixin {
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = FixedChanceRewardPolicy.class, name = "FIXED"),
            @JsonSubTypes.Type(value = VariableChanceRewardPolicy.class, name = "VARIABLE")
    })
    private interface RewardPolicyMixin {
    }

    static final JsonMapper MAPPER = JsonMapper.builder()
            .addMixIn(ContributionPolicy.class, ContributionPolicyMixin.class)
            .addMixIn(RewardPolicy.class, RewardPolicyMixin.class)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private PolicyJson() {
    }
}
