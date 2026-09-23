package com.sporty.jackpot.messaging;

import com.sporty.jackpot.domain.model.Bet;
import org.springframework.stereotype.Component;

/**
 * Maps between the domain {@link Bet} and its Kafka payload {@link BetPlacedEvent}.
 */
@Component
public class BetEventMapper {

    /**
     * @param bet the validated bet
     * @return the Kafka payload
     */
    public BetPlacedEvent toEvent(Bet bet) {
        return new BetPlacedEvent(bet.betId(), bet.userId(), bet.jackpotId(), bet.amount(), bet.placedAt());
    }

    /**
     * @param event the consumed payload
     * @return the validated bet
     * @throws com.sporty.jackpot.exception.InvalidBetException when the payload violates a bet invariant
     */
    public Bet toBet(BetPlacedEvent event) {
        return new Bet(event.betId(), event.userId(), event.jackpotId(), event.betAmount(), event.placedAt());
    }
}
