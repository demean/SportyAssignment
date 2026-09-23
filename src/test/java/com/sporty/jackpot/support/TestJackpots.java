package com.sporty.jackpot.support;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Creates test jackpots with unique ids (never touch the seeded ones) and reads their state back, via plain JDBC.
 * Policy JSON is stored verbatim, so malformed or invalid policies can be inserted as well.
 *
 * <pre>{@code
 * TestJackpots jackpots = new TestJackpots(jdbcTemplate);
 * String id = jackpots.create("1000.00", TestJackpots.fixedContribution("5.0"), TestJackpots.fixedChance("0"));
 * assertThat(jackpots.pool(id)).isEqualByComparingTo("1000.00");
 * }</pre>
 *
 * <p>Numeric arguments are JSON/SQL number literals given as strings (e.g. {@code "5.0"}, {@code "100"}).
 */
public final class TestJackpots {

    private static final String INSERT = """
            INSERT INTO jackpot (id, name, initial_pool_amount, current_pool_amount, contribution_policy,
                                 reward_policy, pool_cycle, version, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""";

    private final JdbcTemplate jdbcTemplate;

    public TestJackpots(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Unique id usable as bet, user or jackpot id ({@code prefix} at most 27 characters).
     *
     * @param prefix readable prefix
     * @return {@code prefix-<uuid>}
     */
    public static String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /**
     * Inserts a jackpot with a unique id, the pool at its initial value and cycle 1.
     *
     * @param initialPool            initial (= current) pool, e.g. {@code "1000.00"}
     * @param contributionPolicyJson contribution policy JSON (see the factory methods)
     * @param rewardPolicyJson       reward policy JSON (see the factory methods)
     * @return the new jackpot id
     */
    public String create(String initialPool, String contributionPolicyJson, String rewardPolicyJson) {
        String id = uniqueId("test-jackpot");
        insert(id, initialPool, initialPool, 1, contributionPolicyJson, rewardPolicyJson);
        return id;
    }

    /**
     * Inserts a jackpot configured like the seeded {@code jackpot-lucky}: initial pool 100.00, 20 % contribution
     * (decaying 1 % per 100 above the initial pool, floor 5 %), chance 5 % + 10 % per 10 above the initial pool,
     * 100 % from a pool of 150. A 250.00 bet contributes 50.00, reaches the limit and wins 150.00.
     *
     * @return the new jackpot id
     */
    public String createLuckyLike() {
        return create("100.00", variableContribution("20.0", "5.0", "1.0", "100"),
                variableChance("5.0", "10.0", "10", "150"));
    }

    /**
     * Inserts a jackpot exactly as given.
     *
     * @param id                     jackpot id
     * @param initialPool            initial pool
     * @param currentPool            current pool
     * @param cycle                  current cycle ({@code >= 1})
     * @param contributionPolicyJson contribution policy JSON, stored verbatim
     * @param rewardPolicyJson       reward policy JSON, stored verbatim
     */
    public void insert(String id, String initialPool, String currentPool, long cycle, String contributionPolicyJson,
                       String rewardPolicyJson) {
        jdbcTemplate.update(INSERT, id, "Test " + id, new BigDecimal(initialPool), new BigDecimal(currentPool),
                contributionPolicyJson, rewardPolicyJson, cycle);
    }

    /** @return the current pool of the jackpot */
    public BigDecimal pool(String jackpotId) {
        return jdbcTemplate.queryForObject("SELECT current_pool_amount FROM jackpot WHERE id = ?", BigDecimal.class,
                jackpotId);
    }

    /** @return the current cycle of the jackpot */
    public long cycle(String jackpotId) {
        return jdbcTemplate.queryForObject("SELECT pool_cycle FROM jackpot WHERE id = ?", Long.class, jackpotId);
    }

    /** @return the optimistic-lock version of the jackpot row */
    public long version(String jackpotId) {
        return jdbcTemplate.queryForObject("SELECT version FROM jackpot WHERE id = ?", Long.class, jackpotId);
    }

    /** @return the sum of all contributions to the jackpot (0 when none) */
    public BigDecimal contributionSum(String jackpotId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(contribution_amount), 0) FROM jackpot_contribution WHERE jackpot_id = ?",
                BigDecimal.class, jackpotId);
    }

    /** @return the sum of all rewards paid by the jackpot (0 when none) */
    public BigDecimal rewardSum(String jackpotId) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(jackpot_reward_amount), 0) FROM jackpot_reward WHERE jackpot_id = ?",
                BigDecimal.class, jackpotId);
    }

    /** @return the number of rewards paid by the jackpot */
    public long rewardCount(String jackpotId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM jackpot_reward WHERE jackpot_id = ?", Long.class,
                jackpotId);
    }

    /** @return {@code {"type":"FIXED","percentage":<percentage>}} */
    public static String fixedContribution(String percentage) {
        return "{\"type\":\"FIXED\",\"percentage\":" + percentage + "}";
    }

    /** @return a {@code VARIABLE} contribution policy JSON */
    public static String variableContribution(String startPercentage, String minPercentage, String decayPercentage,
                                              String poolIncreaseStep) {
        return "{\"type\":\"VARIABLE\",\"startPercentage\":" + startPercentage
                + ",\"minPercentage\":" + minPercentage
                + ",\"decayPercentage\":" + decayPercentage
                + ",\"poolIncreaseStep\":" + poolIncreaseStep + "}";
    }

    /** @return {@code {"type":"FIXED","chancePercentage":<chancePercentage>}} */
    public static String fixedChance(String chancePercentage) {
        return "{\"type\":\"FIXED\",\"chancePercentage\":" + chancePercentage + "}";
    }

    /** @return a {@code VARIABLE} reward policy JSON */
    public static String variableChance(String startChancePercentage, String chanceIncreasePercentage,
                                        String poolIncreaseStep, String poolLimit) {
        return "{\"type\":\"VARIABLE\",\"startChancePercentage\":" + startChancePercentage
                + ",\"chanceIncreasePercentage\":" + chanceIncreasePercentage
                + ",\"poolIncreaseStep\":" + poolIncreaseStep
                + ",\"poolLimit\":" + poolLimit + "}";
    }
}
