package com.sporty.jackpot.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Jackpot read model")
class JackpotTest {

    @Test
    @DisplayName("carries the policies needed to reproduce the deterministic jackpot-lucky demo")
    void luckyDemo() {
        Jackpot lucky = new Jackpot("jackpot-lucky", "Lucky Demo", new BigDecimal("100.00"), new BigDecimal("100.00"), 1L,
                new VariableContributionPolicy(new BigDecimal("20.0"), new BigDecimal("5.0"), new BigDecimal("1.0"),
                        new BigDecimal("100")),
                new VariableChanceRewardPolicy(new BigDecimal("5.0"), new BigDecimal("10.0"), new BigDecimal("10"),
                        new BigDecimal("150")),
                Instant.parse("2026-03-01T12:00:00Z"));

        BigDecimal contribution = lucky.contributionPolicy()
                .contributionAmount(new BigDecimal("250.00"), lucky.currentPoolAmount(), lucky.initialPoolAmount());
        BigDecimal poolAfter = lucky.currentPoolAmount().add(contribution);

        assertThat(lucky.id()).isEqualTo("jackpot-lucky");
        assertThat(lucky.name()).isEqualTo("Lucky Demo");
        assertThat(lucky.cycle()).isEqualTo(1L);
        assertThat(lucky.updatedAt()).isEqualTo(Instant.parse("2026-03-01T12:00:00Z"));
        assertThat(contribution).isEqualTo(new BigDecimal("50.00"));
        assertThat(poolAfter).isEqualTo(new BigDecimal("150.00"));
        assertThat(lucky.rewardPolicy().winChancePercentage(poolAfter, lucky.initialPoolAmount()))
                .isEqualTo(new BigDecimal("100.0000"));
    }
}
