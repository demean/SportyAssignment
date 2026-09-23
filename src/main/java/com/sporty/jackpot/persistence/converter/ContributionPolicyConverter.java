package com.sporty.jackpot.persistence.converter;

import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.hibernate.annotations.Immutable;
import tools.jackson.core.JacksonException;

/**
 * Stores a {@link ContributionPolicy} as JSON. Policies are immutable records, so Hibernate skips deep copies.
 */
@Converter
@Immutable
public class ContributionPolicyConverter implements AttributeConverter<ContributionPolicy, String> {

    @Override
    public String convertToDatabaseColumn(ContributionPolicy attribute) {
        return attribute == null ? null : PolicyJson.MAPPER.writeValueAsString(attribute);
    }

    @Override
    public ContributionPolicy convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        ContributionPolicy policy;
        try {
            policy = PolicyJson.MAPPER.readValue(dbData, ContributionPolicy.class);
        } catch (JacksonException e) {
            throw new JackpotConfigurationException("Invalid contribution policy JSON: " + e.getOriginalMessage(), e);
        }
        if (policy == null) {
            // the JSON literal null is valid JSON, but not a policy
            throw new JackpotConfigurationException("Invalid contribution policy JSON: " + dbData.strip());
        }
        return policy;
    }
}
