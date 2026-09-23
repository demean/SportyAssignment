package com.sporty.jackpot.persistence.repository;

import com.sporty.jackpot.persistence.entity.JackpotContributionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Jackpot contributions (one per contributing bet).
 */
public interface JackpotContributionRepository extends JpaRepository<JackpotContributionEntity, Long> {

    /**
     * @param betId bet id
     * @return the bet's contribution, if the bet contributed
     */
    Optional<JackpotContributionEntity> findByBetId(String betId);
}
