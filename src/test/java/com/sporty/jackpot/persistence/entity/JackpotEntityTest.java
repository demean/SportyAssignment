package com.sporty.jackpot.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("JackpotEntity")
class JackpotEntityTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-01-01T00:00:05.000001Z");
    private static final Instant EVEN_LATER = Instant.parse("2026-01-01T00:00:09Z");

    private static JackpotEntity jackpot(String initialPool, String currentPool, long cycle) {
        return new JackpotEntity("jackpot-1", "Jackpot One", new BigDecimal(initialPool), new BigDecimal(currentPool),
                new FixedContributionPolicy(new BigDecimal("5.0")), new FixedChanceRewardPolicy(BigDecimal.ONE), cycle,
                CREATED_AT);
    }

    @Test
    @DisplayName("the business constructor sets every field, starts at version 0 and marks the creation as last update")
    void constructorSetsEveryField() {
        JackpotEntity jackpot = jackpot("1000.00", "1234.56", 3);

        assertThat(jackpot.getId()).isEqualTo("jackpot-1");
        assertThat(jackpot.getName()).isEqualTo("Jackpot One");
        assertThat(jackpot.getInitialPoolAmount()).isEqualByComparingTo("1000.00");
        assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("1234.56");
        assertThat(jackpot.getContributionPolicy()).isEqualTo(new FixedContributionPolicy(new BigDecimal("5.0")));
        assertThat(jackpot.getRewardPolicy()).isEqualTo(new FixedChanceRewardPolicy(BigDecimal.ONE));
        assertThat(jackpot.getCycle()).isEqualTo(3);
        assertThat(jackpot.getVersion()).isZero();
        assertThat(jackpot.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest(name = "{0} + {1} = {2}")
    @CsvSource({
            "1000.00, 5.00,   1005.00",
            "1000.00, 0.00,   1000.00",
            "1000,    0.5,    1000.50",
            "0.00,    0.01,   0.01",
            "99.99,   0.015,  100.00"})
    @DisplayName("addContribution adds the amount, normalizes the pool to scale 2 and returns the new pool")
    void addContributionReturnsTheNewPool(String pool, String amount, String expectedPool) {
        JackpotEntity jackpot = jackpot("100.00", pool, 1);

        BigDecimal newPool = jackpot.addContribution(new BigDecimal(amount), LATER);

        assertThat(newPool).isEqualByComparingTo(expectedPool).hasScaleOf(2);
        assertThat(jackpot.getCurrentPoolAmount()).isEqualTo(newPool);
        assertThat(jackpot.getUpdatedAt()).isEqualTo(LATER);
        assertThat(jackpot.getCycle()).as("a contribution never starts a new cycle").isEqualTo(1);
        assertThat(jackpot.getInitialPoolAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("consecutive contributions accumulate")
    void contributionsAccumulate() {
        JackpotEntity jackpot = jackpot("1000.00", "1000.00", 1);

        jackpot.addContribution(new BigDecimal("5.00"), LATER);
        BigDecimal pool = jackpot.addContribution(new BigDecimal("2.50"), EVEN_LATER);

        assertThat(pool).isEqualByComparingTo("1007.50");
        assertThat(jackpot.getUpdatedAt()).isEqualTo(EVEN_LATER);
    }

    @Test
    @DisplayName("award returns the whole pool, resets it to the initial value and starts the next cycle")
    void awardPaysOutAndResets() {
        JackpotEntity jackpot = jackpot("100.00", "100.00", 1);
        jackpot.addContribution(new BigDecimal("50.00"), LATER);

        BigDecimal reward = jackpot.award(EVEN_LATER);

        assertThat(reward).isEqualByComparingTo("150.00");
        assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("100.00");
        assertThat(jackpot.getCycle()).isEqualTo(2);
        assertThat(jackpot.getUpdatedAt()).isEqualTo(EVEN_LATER);
    }

    @Test
    @DisplayName("every award starts a new cycle, even when the pool is still at its initial value")
    void everyAwardStartsANewCycle() {
        JackpotEntity jackpot = jackpot("100.00", "100.00", 7);

        BigDecimal first = jackpot.award(LATER);
        BigDecimal second = jackpot.award(EVEN_LATER);

        assertThat(first).isEqualByComparingTo("100.00");
        assertThat(second).isEqualByComparingTo("100.00");
        assertThat(jackpot.getCycle()).isEqualTo(9);
        assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("100.00");
    }
}
