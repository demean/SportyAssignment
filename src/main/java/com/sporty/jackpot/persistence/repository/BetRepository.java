package com.sporty.jackpot.persistence.repository;

import com.sporty.jackpot.persistence.entity.BetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Processed bets, keyed by bet id.
 */
public interface BetRepository extends JpaRepository<BetEntity, String> {
}
