package com.sporty.jackpot.persistence.repository;

import static com.sporty.jackpot.persistence.PersistenceTestSupport.PLACED_AT;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.PROCESSED_AT;
import static com.sporty.jackpot.persistence.PersistenceTestSupport.assertViolates;
import static com.sporty.jackpot.support.TestJackpots.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.persistence.entity.BetEntity;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("BetRepository")
class BetRepositoryTest {

    @Autowired
    private BetRepository betRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final EntityMapper mapper = new EntityMapper();

    @ParameterizedTest
    @EnumSource(BetStatus.class)
    @DisplayName("stores every column of a bet (status as its name) and loads it back as a persisted, not-new entity")
    void storesAndLoadsEveryColumn(BetStatus status) {
        String betId = uniqueId("bet");
        // jackpot_id has no FK: bets for unknown jackpots are stored too
        String unknownJackpotId = uniqueId("unknown-jackpot");

        betRepository.saveAndFlush(new BetEntity(betId, "user-7", unknownJackpotId, new BigDecimal("1000000000.00"),
                status, PLACED_AT, PROCESSED_AT));
        entityManager.clear();

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM bet WHERE bet_id = ?", betId);
        assertThat(row).containsEntry("status", status.name()).containsEntry("user_id", "user-7");

        BetEntity loaded = betRepository.findById(betId).orElseThrow();
        assertThat(loaded.isNew()).as("entities loaded by JPA are never new").isFalse();
        assertThat(mapper.toProcessedBet(loaded)).isEqualTo(new ProcessedBet(betId, "user-7", unknownJackpotId,
                new BigDecimal("1000000000.00"), status, PLACED_AT, PROCESSED_AT));
    }

    @Test
    @DisplayName("findById is empty for a bet that was never processed")
    void findByIdOfAnUnknownBetIsEmpty() {
        assertThat(betRepository.findById(uniqueId("bet"))).isEmpty();
    }

    @Test
    @DisplayName("saving a new bet whose id was committed concurrently INSERTs and fails; the stored bet is untouched")
    void duplicateBetIdViolatesThePrimaryKeyInsteadOfOverwritingTheRow() {
        String betId = uniqueId("bet");
        // another consumer has already stored the bet (the persistence context does not know it)
        jdbcTemplate.update("INSERT INTO bet (bet_id, user_id, jackpot_id, bet_amount, status, placed_at, processed_at)"
                        + " VALUES (?, 'first-user', 'jackpot-a', 10.00, 'CONTRIBUTED', ?, ?)",
                betId, PLACED_AT.atOffset(ZoneOffset.UTC), PROCESSED_AT.atOffset(ZoneOffset.UTC));

        BetEntity duplicate = new BetEntity(betId, "second-user", "jackpot-b", new BigDecimal("99.00"),
                BetStatus.NO_MATCHING_JACKPOT, PLACED_AT, PROCESSED_AT);

        assertViolates("pk_bet", () -> betRepository.saveAndFlush(duplicate));
        assertThat(jdbcTemplate.queryForMap("SELECT user_id, jackpot_id, status FROM bet WHERE bet_id = ?", betId))
                .containsEntry("user_id", "first-user")
                .containsEntry("jackpot_id", "jackpot-a")
                .containsEntry("status", "CONTRIBUTED");
    }

    @Test
    @DisplayName("a non-positive bet amount violates ck_bet_amount")
    void nonPositiveAmountIsRejected() {
        BetEntity bet = new BetEntity(uniqueId("bet"), "user-1", "jackpot-1", new BigDecimal("0.00"),
                BetStatus.CONTRIBUTED, PLACED_AT, PROCESSED_AT);

        assertViolates("ck_bet_amount", () -> betRepository.saveAndFlush(bet));
    }
}
