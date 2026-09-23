package com.sporty.jackpot.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Read model of a stored (processed) bet.
 *
 * @param betId       bet id
 * @param userId      user id
 * @param jackpotId   referenced jackpot id
 * @param betAmount   stake
 * @param status      processing status
 * @param placedAt    when the API accepted the bet
 * @param processedAt when the consumer processed the bet
 */
public record ProcessedBet(String betId, String userId, String jackpotId, BigDecimal betAmount, BetStatus status,
                           Instant placedAt, Instant processedAt) {
}
