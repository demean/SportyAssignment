package com.sporty.jackpot.persistence.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

@DisplayName("PolicyJson")
class PolicyJsonTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.sporty.jackpot.persistence.converter.ConverterPolicyFixtures#allPolicyTypes")
    @DisplayName("registers a JSON subtype for every permitted subclass of both sealed policy interfaces")
    void registersASubtypeForEveryPermittedSubclass(Class<?> policyType) {
        ConverterPolicyFixtures.Sample<?> sample = ConverterPolicyFixtures.sample(policyType);
        Class<?> sealedInterface = ContributionPolicy.class.isAssignableFrom(policyType)
                ? ContributionPolicy.class : RewardPolicy.class;

        String json = PolicyJson.MAPPER.writeValueAsString(sample.policy());
        JsonNode tree = PolicyJson.MAPPER.readTree(json);

        assertThat(tree.get("type").asString()).isEqualTo(sample.typeId());
        assertThat(PolicyJson.MAPPER.readValue(json, sealedInterface)).isEqualTo(sample.policy());
    }

    @Test
    @DisplayName("type ids are unique within each sealed hierarchy")
    void typeIdsAreUniquePerHierarchy() {
        for (Class<?> sealedInterface : List.of(ContributionPolicy.class, RewardPolicy.class)) {
            List<String> typeIds = Arrays.stream(sealedInterface.getPermittedSubclasses())
                    .map(type -> ConverterPolicyFixtures.sample(type).typeId())
                    .toList();
            assertThat(typeIds).as("type ids of %s", sealedInterface.getSimpleName()).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("rejects unknown properties so that typos in stored policies fail loudly")
    void rejectsUnknownProperties() {
        assertThatThrownBy(() -> PolicyJson.MAPPER.readValue(
                "{\"type\":\"FIXED\",\"percentage\":5.0,\"precentage\":6.0}", ContributionPolicy.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("precentage");
    }

    @ParameterizedTest(name = "{0} is written as {1}")
    @MethodSource("plainDecimals")
    @DisplayName("writes BigDecimals in plain notation, never in scientific notation")
    void writesBigDecimalsAsPlainNumbers(String value, String expectedJsonNumber) {
        String json = PolicyJson.MAPPER.writeValueAsString(new FixedContributionPolicy(new BigDecimal(value)));

        assertThat(json).isEqualTo("{\"type\":\"FIXED\",\"percentage\":" + expectedJsonNumber + "}");
    }

    static Stream<Arguments> plainDecimals() {
        return Stream.of(
                Arguments.of("1E+1", "10"),
                Arguments.of("5E-7", "0.0000005"),
                Arguments.of("5.50", "5.50"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"FIXED\",\"percentage\":5.0}",
            "{\"type\":\"FIXED\",\"percentage\":2.0}",
            "{\"type\":\"VARIABLE\",\"startPercentage\":10.0,\"minPercentage\":1.0,\"decayPercentage\":0.5,"
                    + "\"poolIncreaseStep\":1000}",
            "{\"type\":\"VARIABLE\",\"startPercentage\":20.0,\"minPercentage\":5.0,\"decayPercentage\":1.0,"
                    + "\"poolIncreaseStep\":100}"})
    @DisplayName("re-serializes every seeded contribution policy byte-for-byte (pool updates rewrite the column)")
    void seededContributionPoliciesAreCanonical(String seededJson) {
        ContributionPolicy policy = PolicyJson.MAPPER.readValue(seededJson, ContributionPolicy.class);

        assertThat(PolicyJson.MAPPER.writeValueAsString(policy)).isEqualTo(seededJson);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"type\":\"FIXED\",\"chancePercentage\":1.0}",
            "{\"type\":\"VARIABLE\",\"startChancePercentage\":0.1,\"chanceIncreasePercentage\":0.5,"
                    + "\"poolIncreaseStep\":1000,\"poolLimit\":25000}",
            "{\"type\":\"VARIABLE\",\"startChancePercentage\":0.01,\"chanceIncreasePercentage\":0.1,"
                    + "\"poolIncreaseStep\":5000,\"poolLimit\":100000}",
            "{\"type\":\"VARIABLE\",\"startChancePercentage\":5.0,\"chanceIncreasePercentage\":10.0,"
                    + "\"poolIncreaseStep\":10,\"poolLimit\":150}"})
    @DisplayName("re-serializes every seeded reward policy byte-for-byte (pool updates rewrite the column)")
    void seededRewardPoliciesAreCanonical(String seededJson) {
        RewardPolicy policy = PolicyJson.MAPPER.readValue(seededJson, RewardPolicy.class);

        assertThat(PolicyJson.MAPPER.writeValueAsString(policy)).isEqualTo(seededJson);
    }
}
