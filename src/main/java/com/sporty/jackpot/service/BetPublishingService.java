package com.sporty.jackpot.service;

import com.sporty.jackpot.config.JackpotProperties;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.exception.BetPublishingException;
import com.sporty.jackpot.messaging.BetEventMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes accepted bets to the bets topic (keyed by jackpot id) and waits for the broker acknowledgement.
 * Does not touch the database.
 */
@Service
public class BetPublishingService {

    static final String PUBLISHED_COUNTER = "jackpot.bets.published";

    private static final Logger log = LoggerFactory.getLogger(BetPublishingService.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final BetEventMapper betEventMapper;
    private final String betsTopic;
    private final Duration publishTimeout;
    private final Counter published;
    private final Counter failed;

    public BetPublishingService(KafkaTemplate<String, Object> kafkaTemplate, BetEventMapper betEventMapper,
                                JackpotProperties properties, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.betEventMapper = betEventMapper;
        this.betsTopic = properties.kafka().betsTopic();
        this.publishTimeout = properties.kafka().publishTimeout();
        this.published = meterRegistry.counter(PUBLISHED_COUNTER, "result", "success");
        this.failed = meterRegistry.counter(PUBLISHED_COUNTER, "result", "failure");
    }

    /**
     * Publishes a bet and waits for the broker acknowledgement.
     *
     * @param bet the validated bet
     * @return the time the bet was accepted ({@link Bet#placedAt()})
     * @throws BetPublishingException when the broker did not acknowledge in time; the outcome is unknown
     */
    public Instant publish(Bet bet) {
        try {
            RecordMetadata metadata = kafkaTemplate.send(betsTopic, bet.jackpotId(), betEventMapper.toEvent(bet))
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .getRecordMetadata();
            published.increment();
            log.debug("Published bet {} to {}-{}@{}", bet.betId(), metadata.topic(), metadata.partition(),
                    metadata.offset());
            return bet.placedAt();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw publishingFailed(bet, e);
        } catch (ExecutionException | TimeoutException | org.apache.kafka.common.KafkaException
                 | org.springframework.kafka.KafkaException e) {
            throw publishingFailed(bet, e);
        }
    }

    private BetPublishingException publishingFailed(Bet bet, Exception cause) {
        failed.increment();
        log.warn("Publishing bet {} failed, outcome unknown: {}", bet.betId(), cause.toString());
        return new BetPublishingException(bet.betId(), cause);
    }
}
