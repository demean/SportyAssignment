package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.model.Bet;

/**
 * Outbound port of the bet placement use case: hands an accepted bet over to asynchronous processing. The Kafka
 * adapter {@code messaging.BetPublisher} implements it, so the service layer never depends on the messaging layer.
 */
public interface BetEventPublisher {

    /**
     * Publishes a bet and waits until the broker has acknowledged it.
     *
     * @param bet the validated bet
     * @throws com.sporty.jackpot.exception.BetPublishingException when the broker did not acknowledge in time; the
     *                                                             outcome is unknown
     */
    void publish(Bet bet);
}
