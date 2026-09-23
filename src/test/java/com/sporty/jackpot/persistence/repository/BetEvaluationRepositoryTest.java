package com.sporty.jackpot.persistence.repository;

import static com.sporty.jackpot.persistence.PersistenceTestSupport.PROCESSED_AT;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.assertViolates;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.bet;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.contribution;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.evaluation;
import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static com.sporty.jackpot.support.TestJackpots.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.persistence.entity.BetEvaluationEntity;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.support.TestJackpots;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("BetEvaluationRepository")
class BetEvaluationRepositoryTest {

    @Autowired
    private BetEvaluationRepository evaluationRepository;

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

    /** Stores a bet and its contribution (the evaluation's FK target). */
    private String contributedBet() {
        String betId = uniqueId("bet");
        entityManager.persist(bet(betId, jackpotId));
        entityManager.persist(contribution(betId, jackpotId, 1));
        entityManager.flush();
        return betId;
    }

    @ParameterizedTest
    @EnumSource(EvaluationOutcome.class)
    @DisplayName("stores an evaluation (outcome as its name) and finds it by bet id with every column")
    void storesAndFindsByBetId(EvaluationOutcome outcome) {
        String betId = contributedBet();
        BigDecimal reward = outcome == EvaluationOutcome.WON ? new BigDecimal("1000.50") : new BigDecimal("0.00");

        evaluationRepository.saveAndFlush(new BetEvaluationEntity(betId, "user-3", jackpotId, outcome,
                new BigDecimal("12.3456"), reward, 1, PROCESSED_AT));
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject("SELECT outcome FROM bet_evaluation WHERE bet_id = ?", String.class,
                betId)).isEqualTo(outcome.name());
        BetEvaluationEntity loaded = evaluationRepository.findByBetId(betId).orElseThrow();
        assertThat(loaded.getWinChancePercentage()).hasScaleOf(4);
        assertThat(mapper.toEvaluation(loaded)).isEqualTo(new BetEvaluation(betId, "user-3", jackpotId, outcome,
                new BigDecimal("12.3456"), reward, 1, PROCESSED_AT));
    }

    @Test
    @DisplayName("findByBetId is empty for a bet that was not evaluated")
    void findByBetIdWithoutEvaluationIsEmpty() {
        assertThat(evaluationRepository.findByBetId(contributedBet())).isEmpty();
    }

    @Test
    @DisplayName("a bet is evaluated at most once (uk_bet_evaluation_bet)")
    void secondEvaluationOfABetIsRejected() {
        String betId = contributedBet();
        evaluationRepository.saveAndFlush(evaluation(betId, jackpotId, 1));

        assertViolates("uk_bet_evaluation_bet",
                () -> evaluationRepository.saveAndFlush(evaluation(betId, jackpotId, 1)));
    }

    @Test
    @DisplayName("only a contributing bet can be evaluated (fk_bet_evaluation_contribution)")
    void evaluationWithoutContributionIsRejected() {
        String betId = uniqueId("bet");
        entityManager.persistAndFlush(bet(betId, jackpotId));

        assertViolates("fk_bet_evaluation_contribution",
                () -> evaluationRepository.saveAndFlush(evaluation(betId, jackpotId, 1)));
    }

    @Test
    @DisplayName("an evaluation needs an existing jackpot (fk_bet_evaluation_jackpot)")
    void evaluationOfAnUnknownJackpotIsRejected() {
        String betId = contributedBet();

        assertViolates("fk_bet_evaluation_jackpot",
                () -> evaluationRepository.saveAndFlush(evaluation(betId, uniqueId("jackpot"), 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0000", "100.0000"})
    @DisplayName("win chances at the bounds 0 % and 100 % are accepted")
    void chanceBoundsAreAccepted(String chance) {
        String betId = contributedBet();

        evaluationRepository.saveAndFlush(evaluation(betId, jackpotId, 1, new BigDecimal(chance)));
        entityManager.clear();

        assertThat(evaluationRepository.findByBetId(betId).orElseThrow().getWinChancePercentage())
                .isEqualByComparingTo(chance);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.0001", "100.0001"})
    @DisplayName("win chances outside [0, 100] violate ck_bet_evaluation_chance")
    void chanceOutOfBoundsIsRejected(String chance) {
        String betId = contributedBet();

        assertViolates("ck_bet_evaluation_chance",
                () -> evaluationRepository.saveAndFlush(evaluation(betId, jackpotId, 1, new BigDecimal(chance))));
    }
}
