package com.sporty.jackpot.persistence.repository;

import static com.sporty.jackpot.persistence.PersistenceTestSupport.PROCESSED_AT;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.assertViolates;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.bet;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.contribution;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.reward;
import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static com.sporty.jackpot.support.TestJackpots.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.persistence.entity.BetEntity;
import com.sporty.jackpot.persistence.entity.JackpotRewardEntity;
import com.sporty.jackpot.support.TestJackpots;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("JackpotRewardRepository")
class JackpotRewardRepositoryTest {

    @Autowired
    private JackpotRewardRepository rewardRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestJackpots jackpots;

    private String jackpotId;

    @BeforeEach
    void setUp() {
        jackpots = new TestJackpots(jdbcTemplate);
        jackpotId = jackpots.create("1000.00", fixedContribution("5.0"), fixedChance("100"));
    }

    /** Stores a bet and its contribution (the reward's FK target) in the given jackpot cycle. */
    private String contributedBet(String jackpot, long cycle) {
        String betId = uniqueId("bet");
        entityManager.persist(bet(betId, jackpot));
        entityManager.persist(contribution(betId, jackpot, cycle));
        entityManager.flush();
        return betId;
    }

    @Test
    @DisplayName("stores a reward and finds it by bet id with every column")
    void storesAndFindsByBetId() {
        String betId = contributedBet(jackpotId, 4);

        rewardRepository.saveAndFlush(new JackpotRewardEntity(betId, "user-5", jackpotId,
                new BigDecimal("1050.25"), 4, PROCESSED_AT));
        entityManager.clear();

        JackpotRewardEntity loaded = rewardRepository.findByBetId(betId).orElseThrow();
        assertThat(loaded.getBetId()).isEqualTo(betId);
        assertThat(loaded.getUserId()).isEqualTo("user-5");
        assertThat(loaded.getJackpotId()).isEqualTo(jackpotId);
        assertThat(loaded.getJackpotRewardAmount()).isEqualByComparingTo("1050.25").hasScaleOf(2);
        assertThat(loaded.getPoolCycle()).isEqualTo(4);
        assertThat(loaded.getCreatedAt()).isEqualTo(PROCESSED_AT);
        assertThat(jackpots.rewardSum(jackpotId)).isEqualByComparingTo("1050.25");
    }

    @Test
    @DisplayName("findByBetId is empty for a bet that did not win")
    void findByBetIdOfALosingBetIsEmpty() {
        assertThat(rewardRepository.findByBetId(contributedBet(jackpotId, 1))).isEmpty();
    }

    @Test
    @DisplayName("a bet is rewarded at most once (uk_jackpot_reward_bet)")
    void secondRewardOfABetIsRejected() {
        String betId = contributedBet(jackpotId, 1);
        rewardRepository.saveAndFlush(reward(betId, jackpotId, 1));

        assertViolates("uk_jackpot_reward_bet", () -> rewardRepository.saveAndFlush(reward(betId, jackpotId, 2)));
    }

    @Test
    @DisplayName("a jackpot pays out at most once per cycle (uk_jackpot_reward_cycle)")
    void secondRewardInTheSameCycleIsRejected() {
        String winner = contributedBet(jackpotId, 1);
        String secondWinner = contributedBet(jackpotId, 1);
        rewardRepository.saveAndFlush(reward(winner, jackpotId, 1));

        assertViolates("uk_jackpot_reward_cycle",
                () -> rewardRepository.saveAndFlush(reward(secondWinner, jackpotId, 1)));
    }

    @Test
    @DisplayName("the same cycle number can be paid by different jackpots and by later cycles of the same jackpot")
    void cyclesAreUniquePerJackpotOnly() {
        String otherJackpotId = jackpots.create("10.00", fixedContribution("5.0"), fixedChance("100"));
        String first = contributedBet(jackpotId, 1);
        String nextCycle = contributedBet(jackpotId, 2);
        String otherJackpot = contributedBet(otherJackpotId, 1);

        rewardRepository.saveAndFlush(reward(first, jackpotId, 1));
        rewardRepository.saveAndFlush(reward(nextCycle, jackpotId, 2));
        rewardRepository.saveAndFlush(reward(otherJackpot, otherJackpotId, 1));

        assertThat(jackpots.rewardCount(jackpotId)).isEqualTo(2);
        assertThat(jackpots.rewardCount(otherJackpotId)).isEqualTo(1);
    }

    @Test
    @DisplayName("only a contributing bet can be rewarded (fk_jackpot_reward_contribution)")
    void rewardWithoutContributionIsRejected() {
        String betId = uniqueId("bet");
        entityManager.persistAndFlush(new BetEntity(betId, "user-1", jackpotId, BigDecimal.TEN,
                BetStatus.NO_MATCHING_JACKPOT, PROCESSED_AT, PROCESSED_AT));

        assertViolates("fk_jackpot_reward_contribution",
                () -> rewardRepository.saveAndFlush(reward(betId, jackpotId, 1)));
    }

    @Test
    @DisplayName("a reward needs an existing jackpot (fk_jackpot_reward_jackpot)")
    void rewardOfAnUnknownJackpotIsRejected() {
        String betId = contributedBet(jackpotId, 1);

        assertViolates("fk_jackpot_reward_jackpot",
                () -> rewardRepository.saveAndFlush(reward(betId, uniqueId("jackpot"), 1)));
    }
}
