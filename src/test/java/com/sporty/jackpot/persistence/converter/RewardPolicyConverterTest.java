package com.sporty.jackpot.persistence.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import jakarta.persistence.Converter;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;

@DisplayName("RewardPolicyConverter")
class RewardPolicyConverterTest {

    private final RewardPolicyConverter converter = new RewardPolicyConverter();

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.sporty.jackpot.persistence.converter.ConverterPolicyFixtures#rewardPolicyTypes")
    @DisplayName("round-trips every permitted RewardPolicy subclass without loss (type and scale kept)")
    void roundTripsEveryPermittedSubclass(Class<?> policyType) {
        RewardPolicy policy = ConverterPolicyFixtures.rewardSample(policyType);

        String json = converter.convertToDatabaseColumn(policy);
        RewardPolicy restored = converter.convertToEntityAttribute(json);

        assertThat(json).contains("\"type\":\"" + ConverterPolicyFixtures.sample(policyType).typeId() + "\"");
        assertThat(restored).isInstanceOf(policyType).isEqualTo(policy);
    }

    @Test
    @DisplayName("writes the type discriminator followed by the record components as plain numbers")
    void writesTheStoredJsonFormat() {
        assertThat(converter.convertToDatabaseColumn(new FixedChanceRewardPolicy(new BigDecimal("1.0"))))
                .isEqualTo("{\"type\":\"FIXED\",\"chancePercentage\":1.0}");
        assertThat(converter.convertToDatabaseColumn(new VariableChanceRewardPolicy(new BigDecimal("0.1"),
                new BigDecimal("0.5"), new BigDecimal("1E+3"), new BigDecimal("2.5E+4"))))
                .isEqualTo("{\"type\":\"VARIABLE\",\"startChancePercentage\":0.1,\"chanceIncreasePercentage\":0.5,"
                        + "\"poolIncreaseStep\":1000,\"poolLimit\":25000}");
    }

    @Test
    @DisplayName("reads the seeded policy JSON of V2__seed_jackpots.sql")
    void readsTheSeededJson() {
        assertThat(converter.convertToEntityAttribute("{\"type\":\"FIXED\",\"chancePercentage\":1.0}"))
                .isEqualTo(new FixedChanceRewardPolicy(new BigDecimal("1.0")));
        assertThat(converter.convertToEntityAttribute("{\"type\":\"VARIABLE\",\"startChancePercentage\":5.0,"
                + "\"chanceIncreasePercentage\":10.0,\"poolIncreaseStep\":10,\"poolLimit\":150}"))
                .isEqualTo(new VariableChanceRewardPolicy(new BigDecimal("5.0"), new BigDecimal("10.0"),
                        new BigDecimal("10"), new BigDecimal("150")));
    }

    @Test
    @DisplayName("is a JPA converter marked @Immutable: policies are immutable records, never deep-copied (T20)")
    void isAnImmutableConverter() {
        assertThat(RewardPolicyConverter.class).hasAnnotation(Converter.class).hasAnnotation(Immutable.class);
    }

    @Test
    @DisplayName("maps null to null in both directions")
    void mapsNullToNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", " null\n"})
    @DisplayName("rejects the JSON literal null: valid JSON, but not a policy")
    void rejectsJsonNull(String json) {
        assertThatThrownBy(() -> converter.convertToEntityAttribute(json))
                .isInstanceOf(JackpotConfigurationException.class)
                .hasMessage("Invalid reward policy JSON: null")
                .hasNoCause();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            malformed JSON            | {"type":"FIXED",                              | Unexpected end-of-input
            not an object             | "FIXED"                                       | RewardPolicy
            trailing close marker     | {"type":"FIXED","chancePercentage":1.0}}      | Unexpected close marker
            two documents             | {"type":"FIXED","chancePercentage":1.0} {}    | Trailing token
            missing type id           | {"chancePercentage":1.0}                      | missing type id
            unknown type id           | {"type":"JACKPOT","chancePercentage":1.0}     | JACKPOT
            contribution fields       | {"type":"FIXED","percentage":1.0}             | chancePercentage must not be null
            unknown property          | {"type":"FIXED","chancePercentage":1.0,"y":2} | "y"
            missing parameter         | {"type":"FIXED"}                              | chancePercentage must not be null
            chance above 100          | {"type":"FIXED","chancePercentage":101}       | chancePercentage must be within [0, 100] but was 101
            extreme exponent          | {"type":"FIXED","chancePercentage":1e-999999999}  | chancePercentage must have at most 4 decimals but was 1E-999999999
            negative start chance     | {"type":"VARIABLE","startChancePercentage":-0.1,"chanceIncreasePercentage":1,"poolIncreaseStep":1,"poolLimit":10}  | startChancePercentage must be within [0, 100] but was -0.1
            negative chance increase  | {"type":"VARIABLE","startChancePercentage":1,"chanceIncreasePercentage":-1,"poolIncreaseStep":1,"poolLimit":10}    | chanceIncreasePercentage must be >= 0 but was -1
            zero pool increase step   | {"type":"VARIABLE","startChancePercentage":1,"chanceIncreasePercentage":1,"poolIncreaseStep":0,"poolLimit":10}     | poolIncreaseStep must be > 0 but was 0
            zero pool limit           | {"type":"VARIABLE","startChancePercentage":1,"chanceIncreasePercentage":1,"poolIncreaseStep":1,"poolLimit":0}      | poolLimit must be > 0 but was 0
            """)
    @DisplayName("rejects an unusable stored policy with a non-retryable JackpotConfigurationException")
    void rejectsInvalidJson(String description, String json, String expectedDetail) {
        assertThatThrownBy(() -> converter.convertToEntityAttribute(json))
                .isInstanceOf(JackpotConfigurationException.class)
                .hasMessageStartingWith("Invalid reward policy JSON: ")
                .hasMessageContaining(expectedDetail)
                .hasCauseInstanceOf(JacksonException.class)
                .extracting(e -> ((JackpotConfigurationException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
