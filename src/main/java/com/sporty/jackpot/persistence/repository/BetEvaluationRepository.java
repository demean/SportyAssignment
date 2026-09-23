package com.sporty.jackpot.persistence.repository;

import com.sporty.jackpot.persistence.entity.BetEvaluationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reward evaluations (one per contributing bet).
 */
public interface BetEvaluationRepository extends JpaRepository<BetEvaluationEntity, Long> {

    /**
     * @param betId bet id
     * @return the bet's evaluation, if the bet contributed
     */
    Optional<BetEvaluationEntity> findByBetId(String betId);
}
