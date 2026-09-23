package com.sporty.jackpot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads a jackpot's money trail (contributions, rewards, evaluations) straight from the database and checks that it
 * is consistent, independently of the service's own read paths. Plain SQL valid on H2 (MODE=PostgreSQL) and
 * PostgreSQL.
 */
final class JackpotLedger {

    private final JdbcTemplate jdbcTemplate;

    JackpotLedger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** One {@code jackpot_contribution} row. */
    record ContributionRow(String betId, BigDecimal contributionAmount, BigDecimal currentJackpotAmount,
                           long poolCycle) {
    }

    /** One {@code jackpot_reward} row. */
    record RewardRow(String betId, BigDecimal rewardAmount, long poolCycle) {
    }

    /** The {@code jackpot} row. */
    record JackpotRow(BigDecimal initialPool, BigDecimal currentPool, long cycle, long version) {
    }

    JackpotRow jackpot(String jackpotId) {
        return jdbcTemplate.queryForObject("""
                        SELECT initial_pool_amount, current_pool_amount, pool_cycle, version
                        FROM jackpot WHERE id = ?""",
                (rs, row) -> new JackpotRow(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getLong(3), rs.getLong(4)),
                jackpotId);
    }

    List<ContributionRow> contributions(String jackpotId) {
        return jdbcTemplate.query("""
                        SELECT bet_id, contribution_amount, current_jackpot_amount, pool_cycle
                        FROM jackpot_contribution WHERE jackpot_id = ?""",
                (rs, row) -> new ContributionRow(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getLong(4)),
                jackpotId);
    }

    List<RewardRow> rewards(String jackpotId) {
        return jdbcTemplate.query("""
                        SELECT bet_id, jackpot_reward_amount, pool_cycle
                        FROM jackpot_reward WHERE jackpot_id = ? ORDER BY pool_cycle""",
                (rs, row) -> new RewardRow(rs.getString(1), rs.getBigDecimal(2), rs.getLong(3)),
                jackpotId);
    }

    /** @return the pool cycles of the jackpot's evaluations with that outcome */
    List<Long> evaluationCycles(String jackpotId, String outcome) {
        return jdbcTemplate.queryForList(
                "SELECT pool_cycle FROM bet_evaluation WHERE jackpot_id = ? AND outcome = ? ORDER BY pool_cycle",
                Long.class, jackpotId, outcome);
    }

    /** @return the bet's row count in {@code table} (bet, jackpot_contribution, jackpot_reward, bet_evaluation) */
    long rowsOfBet(String table, String betId) {
        if (!List.of("bet", "jackpot_contribution", "jackpot_reward", "bet_evaluation").contains(table)) {
            throw new IllegalArgumentException("Unknown table " + table);
        }
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE bet_id = ?", Long.class, betId);
    }

    /** @return the jackpot id stored on the bet row */
    String jackpotOfBet(String betId) {
        return jdbcTemplate.queryForObject("SELECT jackpot_id FROM bet WHERE bet_id = ?", String.class, betId);
    }

    /**
     * Records, behind the service's back, that {@code cycle} of the jackpot has already been paid out to another bet
     * (bet + contribution + reward rows), without touching the jackpot row. Paying out that cycle again violates
     * {@code UNIQUE(jackpot_id, pool_cycle)}.
     *
     * @param jackpotId jackpot
     * @param cycle     the cycle to mark as paid out
     * @param betId     id of the fixture bet
     */
    void insertPaidOutCycle(String jackpotId, long cycle, String betId) {
        jdbcTemplate.update("""
                INSERT INTO bet (bet_id, user_id, jackpot_id, bet_amount, status, placed_at, processed_at)
                VALUES (?, 'fixture-user', ?, 10.00, 'CONTRIBUTED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)""",
                betId, jackpotId);
        jdbcTemplate.update("""
                INSERT INTO jackpot_contribution (id, bet_id, user_id, jackpot_id, stake_amount, contribution_amount,
                                                  current_jackpot_amount, pool_cycle, created_at)
                VALUES (nextval('jackpot_contribution_seq'), ?, 'fixture-user', ?, 10.00, 1.00, 1.00, ?,
                        CURRENT_TIMESTAMP)""",
                betId, jackpotId, cycle);
        jdbcTemplate.update("""
                INSERT INTO jackpot_reward (id, bet_id, user_id, jackpot_id, jackpot_reward_amount, pool_cycle,
                                            created_at)
                VALUES (nextval('jackpot_reward_seq'), ?, 'fixture-user', ?, 1.00, ?, CURRENT_TIMESTAMP)""",
                betId, jackpotId, cycle);
    }

    /** Removes the reward row written by {@link #insertPaidOutCycle(String, long, String)}. */
    void deletePaidOutReward(String betId) {
        jdbcTemplate.update("DELETE FROM jackpot_reward WHERE bet_id = ?", betId);
    }

    /**
     * Asserts that the jackpot's stored money trail is consistent. Requires that every change of the jackpot went
     * through the processing service, starting from cycle 1, version 0 and the pool at its initial value:
     * <ul>
     *   <li>no lost update: within each cycle the contributions form a gap-free chain, each one's
     *       "current jackpot amount" is the previous one's plus its own contribution, starting from the initial pool;
     *   <li>every closed cycle was paid out exactly once, to the bet that closed it, with the whole pool of that
     *       moment; the open cycle's chain ends at the current pool;
     *   <li>conservation: pool = initial + Σ contributions − Σ (reward − initial), since each payout takes the
     *       whole pool and the reset re-seeds it with the initial value;
     *   <li>the optimistic-lock version counts exactly one versioned {@code UPDATE} per pool change: one per
     *       contribution and one more per payout (the payout + reset is flushed together with the reward insert).
     * </ul>
     *
     * @return the jackpot's ledger, for further scenario-specific assertions
     */
    Snapshot assertConsistent(String jackpotId) {
        JackpotRow jackpot = jackpot(jackpotId);
        List<ContributionRow> contributions = contributions(jackpotId);
        List<RewardRow> rewards = rewards(jackpotId);

        assertThat(rewards).extracting(RewardRow::poolCycle)
                .as("every closed cycle is paid out exactly once")
                .containsExactlyElementsOf(LongStream.range(1, jackpot.cycle()).boxed().toList());
        Map<Long, RewardRow> rewardByCycle = rewards.stream()
                .collect(Collectors.toMap(RewardRow::poolCycle, Function.identity()));

        Map<Long, List<ContributionRow>> contributionsByCycle = new TreeMap<>(contributions.stream()
                .collect(Collectors.groupingBy(ContributionRow::poolCycle)));
        assertThat(contributionsByCycle.keySet()).allSatisfy(cycle -> assertThat(cycle).isBetween(1L, jackpot.cycle()));
        for (long cycle = 1; cycle <= jackpot.cycle(); cycle++) {
            List<ContributionRow> chain = contributionsByCycle.getOrDefault(cycle, List.of()).stream()
                    .sorted(Comparator.comparing(ContributionRow::currentJackpotAmount))
                    .toList();
            BigDecimal pool = jackpot.initialPool();
            for (ContributionRow contribution : chain) {
                assertThat(contribution.currentJackpotAmount())
                        .as("pool after bet %s in cycle %d = pool before + its contribution (no lost update)",
                                contribution.betId(), cycle)
                        .isEqualByComparingTo(pool.add(contribution.contributionAmount()));
                pool = contribution.currentJackpotAmount();
            }
            if (cycle < jackpot.cycle()) {
                RewardRow reward = rewardByCycle.get(cycle);
                assertThat(chain).as("cycle %d has contributions", cycle).isNotEmpty();
                assertThat(reward.betId()).as("cycle %d is won by the bet that closed it", cycle)
                        .isEqualTo(chain.getLast().betId());
                assertThat(reward.rewardAmount()).as("cycle %d pays out the whole pool", cycle)
                        .isEqualByComparingTo(pool);
            } else {
                assertThat(jackpot.currentPool()).as("the open cycle %d ends at the current pool", cycle)
                        .isEqualByComparingTo(pool);
            }
        }

        BigDecimal contributed = sum(contributions.stream().map(ContributionRow::contributionAmount).toList());
        BigDecimal paidOut = sum(rewards.stream().map(RewardRow::rewardAmount).toList());
        BigDecimal reseeded = jackpot.initialPool().multiply(BigDecimal.valueOf(rewards.size()));
        assertThat(jackpot.currentPool()).as("pool = initial + Σ contributions − Σ rewards + initial per reset")
                .isEqualByComparingTo(jackpot.initialPool().add(contributed).subtract(paidOut).add(reseeded));
        assertThat(jackpot.version()).as("one versioned update per contribution and per payout")
                .isEqualTo(contributions.size() + rewards.size());
        return new Snapshot(jackpot, contributions, rewards);
    }

    /**
     * A jackpot's ledger as checked by {@link #assertConsistent(String)}.
     *
     * @param jackpot       the jackpot row
     * @param contributions its contributions
     * @param rewards       its rewards, by cycle
     */
    record Snapshot(JackpotRow jackpot, List<ContributionRow> contributions, List<RewardRow> rewards) {

        BigDecimal contributed() {
            return sum(contributions.stream().map(ContributionRow::contributionAmount).toList());
        }
    }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
