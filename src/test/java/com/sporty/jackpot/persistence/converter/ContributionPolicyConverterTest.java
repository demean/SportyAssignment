package com.sporty.jackpot.persistence.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;

@DisplayName("ContributionPolicyConverter")
class ContributionPolicyConverterTest {

    private final ContributionPolicyConverter converter = new ContributionPolicyConverter();

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.sporty.jackpot.persistence.converter.ConverterPolicyFixtures#contributionPolicyTypes")
    @DisplayName("round-trips every permitted ContributionPolicy subclass without loss (type and scale kept)")
    void roundTripsEveryPermittedSubclass(Class<?> policyType) {
        ContributionPolicy policy = ConverterPolicyFixtures.contributionSample(policyType);

        String json = converter.convertToDatabaseColumn(policy);
        ContributionPolicy restored = converter.convertToEntityAttribute(json);

        assertThat(json).contains("\"type\":\"" + ConverterPolicyFixtures.sample(policyType).typeId() + "\"");
        assertThat(restored).isInstanceOf(policyType).isEqualTo(policy);
    }

    @Test
    @DisplayName("writes the type discriminator followed by the record components as plain numbers")
    void writesTheStoredJsonFormat() {
        assertThat(converter.convertToDatabaseColumn(new FixedContributionPolicy(new BigDecimal("5.0"))))
                .isEqualTo("{\"type\":\"FIXED\",\"percentage\":5.0}");
        assertThat(converter.convertToDatabaseColumn(new VariableContributionPolicy(new BigDecimal("10.0"),
                new BigDecimal("1.0"), new BigDecimal("0.5"), new BigDecimal("1E+3"))))
                .isEqualTo("{\"type\":\"VARIABLE\",\"startPercentage\":10.0,\"minPercentage\":1.0,"
                        + "\"decayPercentage\":0.5,\"poolIncreaseStep\":1000}");
    }

    @Test
    @DisplayName("reads the seeded policy JSON of V2__seed_jackpots.sql")
    void readsTheSeededJson() {
        assertThat(converter.convertToEntityAttribute("{\"type\":\"FIXED\",\"percentage\":2.0}"))
                .isEqualTo(new FixedContributionPolicy(new BigDecimal("2.0")));
        assertThat(converter.convertToEntityAttribute("{\"type\":\"VARIABLE\",\"startPercentage\":20.0,"
                + "\"minPercentage\":5.0,\"decayPercentage\":1.0,\"poolIncreaseStep\":100}"))
                .isEqualTo(new VariableContributionPolicy(new BigDecimal("20.0"), new BigDecimal("5.0"),
                        new BigDecimal("1.0"), new BigDecimal("100")));
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
                .hasMessage("Invalid contribution policy JSON: null")
                .hasNoCause();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            malformed JSON            | {"type":"FIXED","percentage":            | Unexpected end-of-input
            not an object             | [5.0]                                    | ContributionPolicy
            empty document            | ''                                       | No content to map
            trailing close marker     | {"type":"FIXED","percentage":5.0}}       | Unexpected close marker
            two documents             | {"type":"FIXED","percentage":5.0}{}      | Trailing token
            missing type id           | {"percentage":5.0}                       | missing type id
            unknown type id           | {"type":"PROGRESSIVE","percentage":5.0}  | PROGRESSIVE
            reward policy fields      | {"type":"FIXED","chancePercentage":5.0}  | percentage must not be null
            unknown property          | {"type":"FIXED","percentage":5.0,"x":1}  | "x"
            missing parameter         | {"type":"FIXED"}                         | percentage must not be null
            percentage above 100      | {"type":"FIXED","percentage":100.01}     | percentage must be within [0, 100] but was 100.01
            negative percentage       | {"type":"FIXED","percentage":-1}         | percentage must be within [0, 100] but was -1
            min above start           | {"type":"VARIABLE","startPercentage":1,"minPercentage":2,"decayPercentage":0,"poolIncreaseStep":1}   | minPercentage (2) must not exceed startPercentage (1)
            zero pool increase step   | {"type":"VARIABLE","startPercentage":2,"minPercentage":1,"decayPercentage":0,"poolIncreaseStep":0}   | poolIncreaseStep must be > 0 but was 0
            negative decay            | {"type":"VARIABLE","startPercentage":2,"minPercentage":1,"decayPercentage":-1,"poolIncreaseStep":1}  | decayPercentage must be >= 0 but was -1
            """)
    @DisplayName("rejects an unusable stored policy with a non-retryable JackpotConfigurationException")
    void rejectsInvalidJson(String description, String json, String expectedDetail) {
        assertThatThrownBy(() -> converter.convertToEntityAttribute(json))
                .isInstanceOf(JackpotConfigurationException.class)
                .hasMessageStartingWith("Invalid contribution policy JSON: ")
                .hasMessageContaining(expectedDetail)
                .hasCauseInstanceOf(JacksonException.class)
                .extracting(e -> ((JackpotConfigurationException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }
}
