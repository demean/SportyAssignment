package com.sporty.jackpot.persistence.repository;

import static com.sporty.jackpot.persistence.PersistenceTestSupport.PROCESSED_AT;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.assertViolates;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.bet;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.contribution;
import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static com.sporty.jackpot.support.TestJackpots.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.persistence.entity.JackpotContributionEntity;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
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
@DisplayName("JackpotContributionRepository")
class JackpotContributionRepositoryTest {

    @Autowired
    private JackpotContributionRepository contributionRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final EntityMapper mapper = new EntityMapper();

    private String jackpotId;

    @BeforeEach
    void setUp() {
        jackpotId = new TestJackpots(jdbcTemplate).create("1000.00", fixedContribution("5.0"), fixedChance("1.0"));
    }

    private String persistedBet() {
        String betId = uniqueId("bet");
        entityManager.persist(bet(betId, jackpotId));
        return betId;
    }

    @Test
    @DisplayName("stores a contribution with a sequence id and finds it by bet id with every column")
    void storesAndFindsByBetId() {
        String betId = persistedBet();

        contributionRepository.saveAndFlush(new JackpotContributionEntity(betId, "user-9", jackpotId,
                new BigDecimal("250.00"), new BigDecimal("12.50"), new BigDecimal("1012.50"), 3, PROCESSED_AT));
        entityManager.clear();

        JackpotContributionEntity loaded = contributionRepository.findByBetId(betId).orElseThrow();
        assertThat(mapper.toContribution(loaded)).isEqualTo(new Contribution(betId, "user-9", jackpotId,
                new BigDecimal("250.00"), new BigDecimal("12.50"), new BigDecimal("1012.50"), 3, PROCESSED_AT));
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM jackpot_contribution WHERE bet_id = ?", Long.class,
                betId)).isPositive();
    }

    @Test
    @DisplayName("a contribution that rounds to 0.00 is recorded (micro-bet guard)")
    void zeroContributionIsRecorded() {
        String betId = persistedBet();

        contributionRepository.saveAndFlush(new JackpotContributionEntity(betId, "user-1", jackpotId,
                new BigDecimal("0.01"), new BigDecimal("0.00"), new BigDecimal("1000.00"), 1, PROCESSED_AT));
        entityManager.clear();

        assertThat(contributionRepository.findByBetId(betId).orElseThrow().getContributionAmount())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("findByBetId is empty for a bet without contribution")
    void findByBetIdWithoutContributionIsEmpty() {
        assertThat(contributionRepository.findByBetId(persistedBet())).isEmpty();
    }

    @Test
    @DisplayName("a bet contributes at most once (uk_jackpot_contribution_bet)")
    void secondContributionOfABetIsRejected() {
        String betId = persistedBet();
        contributionRepository.saveAndFlush(contribution(betId, jackpotId, 1));

        assertViolates("uk_jackpot_contribution_bet",
                () -> contributionRepository.saveAndFlush(contribution(betId, jackpotId, 1)));
    }

    @Test
    @DisplayName("a contribution needs a stored bet (fk_jackpot_contribution_bet)")
    void contributionOfAnUnknownBetIsRejected() {
        assertViolates("fk_jackpot_contribution_bet",
                () -> contributionRepository.saveAndFlush(contribution(uniqueId("bet"), jackpotId, 1)));
    }

    @Test
    @DisplayName("a contribution needs an existing jackpot (fk_jackpot_contribution_jackpot)")
    void contributionToAnUnknownJackpotIsRejected() {
        String betId = persistedBet();

        assertViolates("fk_jackpot_contribution_jackpot",
                () -> contributionRepository.saveAndFlush(contribution(betId, uniqueId("jackpot"), 1)));
    }
}
