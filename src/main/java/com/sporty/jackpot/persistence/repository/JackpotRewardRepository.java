package com.sporty.jackpot.persistence.repository;

import com.sporty.jackpot.persistence.entity.JackpotRewardEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Jackpot payouts (one per winning bet, one per jackpot cycle).
 */
public interface JackpotRewardRepository extends JpaRepository<JackpotRewardEntity, Long> {

    /**
     * @param betId bet id
     * @return the reward paid for the bet, if it won
     */
    Optional<JackpotRewardEntity> findByBetId(String betId);
}
