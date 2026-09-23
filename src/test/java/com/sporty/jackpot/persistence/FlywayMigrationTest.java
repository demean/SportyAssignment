package com.sporty.jackpot.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import com.sporty.jackpot.persistence.entity.JackpotEntity;
import com.sporty.jackpot.persistence.repository.JackpotRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The Flyway migrations {@code V1__create_jackpot_schema.sql} and {@code V2__seed_jackpots.sql} on H2
 * (MODE=PostgreSQL): applied history, seeded jackpots, initial-pool placeholders and every CHECK constraint.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Flyway migrations")
class FlywayMigrationTest {

    private static final OffsetDateTime TIMESTAMP = PersistenceTestSupport.PROCESSED_AT.atOffset(ZoneOffset.UTC);
    private static final String PARENT_BET = "check-parent-bet";
    private static final String CONTRIBUTION_BET = "check-contribution-bet";
    private static final String PARENT_JACKPOT = "check-parent-jackpot";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JackpotRepository jackpotRepository;

    @Test
    @DisplayName("applies V1 (schema) and V2 (seed) successfully on H2 in PostgreSQL mode")
    void appliesBothMigrations() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'", String.class))
                .isEqualTo("PostgreSQL");
        assertThat(jdbcTemplate.query(
                "SELECT version, description, success FROM flyway_schema_history WHERE version IS NOT NULL"
                        + " ORDER BY installed_rank",
                (rs, row) -> tuple(rs.getString("version"), rs.getString("description"), rs.getBoolean("success"))))
                .containsExactly(
                        tuple("1", "create jackpot schema", true),
                        tuple("2", "seed jackpots", true));
    }

    @Test
    @DisplayName("seeds the four documented jackpots at their default initial pools, cycle 1, version 0")
    void seedsTheDocumentedJackpots() {
        Map<String, JackpotEntity> seeds = Stream.of("jackpot-fixed", "jackpot-variable", "jackpot-mixed",
                        "jackpot-lucky")
                .map(id -> jackpotRepository.findById(id).orElseThrow())
                .collect(Collectors.toMap(JackpotEntity::getId, jackpot -> jackpot));

        assertSeed(seeds.get("jackpot-fixed"), "Fixed Classic", "1000.00",
                new FixedContributionPolicy(new BigDecimal("5.0")),
                new FixedChanceRewardPolicy(new BigDecimal("1.0")));
        assertSeed(seeds.get("jackpot-variable"), "Variable Progressive", "5000.00",
                new VariableContributionPolicy(new BigDecimal("10.0"), new BigDecimal("1.0"),
                        new BigDecimal("0.5"), new BigDecimal("1000")),
                new VariableChanceRewardPolicy(new BigDecimal("0.1"), new BigDecimal("0.5"),
                        new BigDecimal("1000"), new BigDecimal("25000")));
        assertSeed(seeds.get("jackpot-mixed"), "Mixed Mega", "10000.00",
                new FixedContributionPolicy(new BigDecimal("2.0")),
                new VariableChanceRewardPolicy(new BigDecimal("0.01"), new BigDecimal("0.1"),
                        new BigDecimal("5000"), new BigDecimal("100000")));
        assertSeed(seeds.get("jackpot-lucky"), "Lucky Demo", "100.00",
                new VariableContributionPolicy(new BigDecimal("20.0"), new BigDecimal("5.0"),
                        new BigDecimal("1.0"), new BigDecimal("100")),
                new VariableChanceRewardPolicy(new BigDecimal("5.0"), new BigDecimal("10.0"),
                        new BigDecimal("10"), new BigDecimal("150")));
    }

    private static void assertSeed(JackpotEntity jackpot, String name, String initialPool, Object contributionPolicy,
                                   Object rewardPolicy) {
        assertThat(jackpot.getName()).isEqualTo(name);
        assertThat(jackpot.getInitialPoolAmount()).isEqualByComparingTo(initialPool).hasScaleOf(2);
        assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo(initialPool);
        assertThat(jackpot.getCycle()).isEqualTo(1);
        assertThat(jackpot.getVersion()).isZero();
        assertThat(jackpot.getContributionPolicy()).isEqualTo(contributionPolicy);
        assertThat(jackpot.getRewardPolicy()).isEqualTo(rewardPolicy);
        assertThat(jackpot.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("every seeded jackpot passes the cross-field policy validation against its initial pool")
    void seedsAreConsistent() {
        assertThat(jackpotRepository.findAllById(List.of("jackpot-fixed", "jackpot-variable", "jackpot-mixed",
                "jackpot-lucky")))
                .hasSize(4)
                .allSatisfy(jackpot -> {
                    jackpot.getContributionPolicy().validateFor(jackpot.getInitialPoolAmount());
                    jackpot.getRewardPolicy().validateFor(jackpot.getInitialPoolAmount());
                });
    }

    @Test
    @DisplayName("the seeded lucky jackpot reproduces the README demo: 250.00 contributes 50.00 and reaches 100 %")
    void luckySeedReproducesTheDemo() {
        JackpotEntity lucky = jackpotRepository.findById("jackpot-lucky").orElseThrow();
        BigDecimal initial = lucky.getInitialPoolAmount();

        BigDecimal contribution = lucky.getContributionPolicy()
                .contributionAmount(new BigDecimal("250.00"), lucky.getCurrentPoolAmount(), initial);
        BigDecimal poolAfter = lucky.getCurrentPoolAmount().add(contribution);

        assertThat(contribution).isEqualByComparingTo("50.00");
        assertThat(poolAfter).isEqualByComparingTo("150.00");
        assertThat(lucky.getRewardPolicy().winChancePercentage(poolAfter, initial)).isEqualByComparingTo("100");
    }

    @Nested
    @TestPropertySource(properties = {
            "JACKPOT_FIXED_INITIAL_POOL=1234.56",
            "JACKPOT_VARIABLE_INITIAL_POOL=6000.00",
            "JACKPOT_MIXED_INITIAL_POOL=20000",
            "JACKPOT_LUCKY_INITIAL_POOL=120.50"})
    @DisplayName("with initial pools overridden through the JACKPOT_*_INITIAL_POOL variables")
    class OverriddenInitialPools {

        @Autowired
        private JdbcTemplate nestedJdbcTemplate;

        @Test
        @DisplayName("seeds the initial and current pools from the Flyway placeholders")
        void seedsThePlaceholderValues() {
            Map<String, BigDecimal[]> pools = new LinkedHashMap<>();
            nestedJdbcTemplate.query("SELECT id, initial_pool_amount, current_pool_amount FROM jackpot ORDER BY id",
                    rs -> {
                        pools.put(rs.getString("id"), new BigDecimal[] {
                                rs.getBigDecimal("initial_pool_amount"), rs.getBigDecimal("current_pool_amount")});
                    });

            assertThat(pools).containsOnlyKeys("jackpot-fixed", "jackpot-variable", "jackpot-mixed", "jackpot-lucky");
            assertPool(pools.get("jackpot-fixed"), "1234.56");
            assertPool(pools.get("jackpot-variable"), "6000.00");
            assertPool(pools.get("jackpot-mixed"), "20000.00");
            assertPool(pools.get("jackpot-lucky"), "120.50");
        }

        private static void assertPool(BigDecimal[] initialAndCurrent, String expected) {
            assertThat(initialAndCurrent[0]).isEqualByComparingTo(expected);
            assertThat(initialAndCurrent[1]).isEqualByComparingTo(expected);
        }
    }

    // ---------------------------------------------------------------------------------------------- CHECK constraints

    @Nested
    @DisplayName("CHECK constraints")
    class CheckConstraints {

        @BeforeEach
        void insertParents() {
            Map<String, Object> jackpot = validJackpot();
            jackpot.put("id", PARENT_JACKPOT);
            insert("jackpot", jackpot);
            insert("bet", validBet(PARENT_BET));
            insert("bet", validBet(CONTRIBUTION_BET));
            insert("jackpot_contribution", validContribution(PARENT_BET, 1));
        }

        @Test
        @DisplayName("valid rows at the boundaries of every CHECK constraint are accepted")
        void boundaryRowsAreAccepted() {
            Map<String, Object> jackpot = validJackpot();
            jackpot.put("initial_pool_amount", new BigDecimal("0.00"));
            jackpot.put("current_pool_amount", new BigDecimal("0.00"));
            insert("jackpot", jackpot);

            Map<String, Object> contribution = validContribution(CONTRIBUTION_BET, 2);
            contribution.put("stake_amount", new BigDecimal("0.01"));
            contribution.put("contribution_amount", new BigDecimal("0.00"));
            contribution.put("current_jackpot_amount", new BigDecimal("0.00"));
            insert("jackpot_contribution", contribution);

            Map<String, Object> reward = validReward();
            reward.put("jackpot_reward_amount", new BigDecimal("0.00"));
            insert("jackpot_reward", reward);

            Map<String, Object> evaluation = validEvaluation();
            evaluation.put("outcome", "WON");
            evaluation.put("win_chance_percentage", new BigDecimal("100.0000"));
            evaluation.put("reward_amount", new BigDecimal("0.00"));
            insert("bet_evaluation", evaluation);

            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bet_evaluation WHERE bet_id = ?",
                    Integer.class, PARENT_BET)).isOne();
        }

        @ParameterizedTest(name = "{0}: {1}.{2} = {3}")
        @MethodSource("com.sporty.jackpot.persistence.FlywayMigrationTest#checkViolations")
        @DisplayName("rows violating a CHECK constraint are rejected by that constraint")
        void violatingRowsAreRejected(String constraint, String table, String column, Object value) {
            Map<String, Object> row = switch (table) {
                case "jackpot" -> validJackpot();
                case "bet" -> validBet("check-new-bet");
                case "jackpot_contribution" -> validContribution(CONTRIBUTION_BET, 1);
                case "jackpot_reward" -> validReward();
                case "bet_evaluation" -> validEvaluation();
                default -> throw new IllegalArgumentException(table);
            };
            row.put(column, value);

            PersistenceTestSupport.assertViolates(constraint, () -> insert(table, row));
        }
    }

    static Stream<Arguments> checkViolations() {
        return Stream.of(
                Arguments.of("ck_jackpot_initial_pool", "jackpot", "initial_pool_amount", new BigDecimal("-0.01")),
                Arguments.of("ck_jackpot_current_pool", "jackpot", "current_pool_amount", new BigDecimal("-0.01")),
                Arguments.of("ck_jackpot_pool_cycle", "jackpot", "pool_cycle", 0L),
                Arguments.of("ck_bet_amount", "bet", "bet_amount", new BigDecimal("0.00")),
                Arguments.of("ck_bet_status", "bet", "status", "PENDING"),
                Arguments.of("ck_jackpot_contribution_stake", "jackpot_contribution", "stake_amount",
                        new BigDecimal("0.00")),
                Arguments.of("ck_jackpot_contribution_amount", "jackpot_contribution", "contribution_amount",
                        new BigDecimal("-0.01")),
                Arguments.of("ck_jackpot_contribution_pool", "jackpot_contribution", "current_jackpot_amount",
                        new BigDecimal("-0.01")),
                Arguments.of("ck_jackpot_reward_amount", "jackpot_reward", "jackpot_reward_amount",
                        new BigDecimal("-0.01")),
                Arguments.of("ck_bet_evaluation_outcome", "bet_evaluation", "outcome", "DRAW"),
                Arguments.of("ck_bet_evaluation_chance", "bet_evaluation", "win_chance_percentage",
                        new BigDecimal("100.0001")),
                Arguments.of("ck_bet_evaluation_chance", "bet_evaluation", "win_chance_percentage",
                        new BigDecimal("-0.0001")),
                Arguments.of("ck_bet_evaluation_reward", "bet_evaluation", "reward_amount", new BigDecimal("-0.01")));
    }

    private void insert(String table, Map<String, Object> row) {
        String columns = String.join(", ", row.keySet());
        String parameters = row.keySet().stream().map(column -> "?").collect(Collectors.joining(", "));
        jdbcTemplate.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + parameters + ")",
                row.values().toArray());
    }

    private static Map<String, Object> validJackpot() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "check-jackpot");
        row.put("name", "Check");
        row.put("initial_pool_amount", new BigDecimal("100.00"));
        row.put("current_pool_amount", new BigDecimal("100.00"));
        row.put("contribution_policy", "{\"type\":\"FIXED\",\"percentage\":5.0}");
        row.put("reward_policy", "{\"type\":\"FIXED\",\"chancePercentage\":1.0}");
        row.put("pool_cycle", 1L);
        row.put("version", 0L);
        row.put("created_at", TIMESTAMP);
        row.put("updated_at", TIMESTAMP);
        return row;
    }

    private static Map<String, Object> validBet(String betId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("bet_id", betId);
        row.put("user_id", "user-1");
        row.put("jackpot_id", PARENT_JACKPOT);
        row.put("bet_amount", new BigDecimal("10.00"));
        row.put("status", "CONTRIBUTED");
        row.put("placed_at", TIMESTAMP);
        row.put("processed_at", TIMESTAMP);
        return row;
    }

    private static Map<String, Object> validContribution(String betId, long id) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", -id);
        row.put("bet_id", betId);
        row.put("user_id", "user-1");
        row.put("jackpot_id", PARENT_JACKPOT);
        row.put("stake_amount", new BigDecimal("10.00"));
        row.put("contribution_amount", new BigDecimal("0.50"));
        row.put("current_jackpot_amount", new BigDecimal("1000.50"));
        row.put("pool_cycle", 1L);
        row.put("created_at", TIMESTAMP);
        return row;
    }

    private static Map<String, Object> validReward() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", -1L);
        row.put("bet_id", PARENT_BET);
        row.put("user_id", "user-1");
        row.put("jackpot_id", PARENT_JACKPOT);
        row.put("jackpot_reward_amount", new BigDecimal("1000.50"));
        row.put("pool_cycle", 1L);
        row.put("created_at", TIMESTAMP);
        return row;
    }

    private static Map<String, Object> validEvaluation() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", -1L);
        row.put("bet_id", PARENT_BET);
        row.put("user_id", "user-1");
        row.put("jackpot_id", PARENT_JACKPOT);
        row.put("outcome", "LOST");
        row.put("win_chance_percentage", new BigDecimal("0.0000"));
        row.put("reward_amount", new BigDecimal("0.00"));
        row.put("pool_cycle", 1L);
        row.put("created_at", TIMESTAMP);
        return row;
    }
}
