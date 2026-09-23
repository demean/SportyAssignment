package com.sporty.jackpot.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kafka payload of the {@code jackpot-bets} topic (JSON, no type headers, record key = {@code jackpotId}).
 *
 * @param betId     bet id
 * @param userId    user id
 * @param jackpotId jackpot id
 * @param betAmount stake
 * @param placedAt  when the API accepted the bet
 */
public record BetPlacedEvent(String betId, String userId, String jackpotId, BigDecimal betAmount, Instant placedAt) {
}
