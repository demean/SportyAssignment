package com.sporty.jackpot.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * Traps T2, T3 and T6 under real concurrency on H2: {@value ConcurrencyScenarios#THREADS} threads released together
 * call the processing service directly (no Kafka), one transaction per call.
 */
@DisplayName("Bet processing under concurrency (H2, no Kafka)")
class BetProcessingConcurrencyTest extends AbstractProcessingServiceIntegrationTest {

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Test
    @DisplayName("the context runs without Kafka consumers: only the test threads process bets")
    void listenerContainersAreNotRunning() {
        assertThat(listenerRegistry.getListenerContainers()).isNotEmpty()
                .noneMatch(MessageListenerContainer::isRunning);
    }

    @Test
    @DisplayName("(a) 96 parallel bets on one jackpot: no lost update, one reward per cycle, consistent version")
    void parallelContributionsOnOneJackpotKeepThePoolConsistent() {
        scenarios.parallelContributionsKeepThePoolConsistent(3, 8);
    }

    @RepeatedTest(value = 3, name = "(b) round {currentRepetition}/{totalRepetitions}")
    @DisplayName("(b) the same bet id raced to two jackpots is processed once, the losing jackpot is untouched")
    void sameBetIdOnTwoJackpotsIsProcessedOnce() {
        scenarios.sameBetIdOnTwoJackpotsIsProcessedOnce();
    }

    @RepeatedTest(value = 3, name = "(b) via listener, round {currentRepetition}/{totalRepetitions}")
    @DisplayName("(b) one record consumed concurrently for two jackpots: the listener acks every loser as duplicate")
    void sameBetIdRacingThroughTheListenerIsAckedOnce() {
        scenarios.sameBetIdRacingThroughTheListenerIsAckedOnce();
    }

    @Test
    @DisplayName("(c) parallel bets on a 100 % jackpot: each wins its own cycle, the pool ends at its initial value")
    void everyParallelBetWinsACertainJackpotInItsOwnCycle() {
        scenarios.everyParallelBetWinsACertainJackpotInItsOwnCycle();
    }
}
