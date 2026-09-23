package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.model.Bet;
import java.math.BigDecimal;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * The place-bet use case: stamps the bet with the time it was accepted, validates it and publishes it for
 * asynchronous processing. It never touches the database (the publish path scales with the broker, not with the
 * database), so it owns no repository and runs in no transaction; whether the jackpot exists is decided by the
 * consumer.
 */
@Service
public class BetPlacementService {

    private final BetEventPublisher publisher;
    private final Clock clock;

    public BetPlacementService(BetEventPublisher publisher, Clock clock) {
        this.publisher = publisher;
        this.clock = clock;
    }

    /**
     * Accepts a bet and publishes it.
     *
     * @param betId     globally unique bet id (idempotency key)
     * @param userId    id of the betting user
     * @param jackpotId id of the jackpot the bet contributes to
     * @param amount    stake
     * @return the published bet; its {@link Bet#placedAt()} is the time the bet was accepted
     * @throws com.sporty.jackpot.exception.InvalidBetException    when the bet violates a domain invariant; nothing
     *                                                             is published
     * @throws com.sporty.jackpot.exception.BetPublishingException when the broker did not acknowledge in time; the
     *                                                             outcome is unknown
     */
    public Bet place(String betId, String userId, String jackpotId, BigDecimal amount) {
        Bet bet = new Bet(betId, userId, jackpotId, amount, clock.instant());
        publisher.publish(bet);
        return bet;
    }
}
