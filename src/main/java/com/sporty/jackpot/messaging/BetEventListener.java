package com.sporty.jackpot.messaging;

import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.ProcessingResult;
import com.sporty.jackpot.service.BetProcessingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code jackpot-bets}. Not transactional itself: each record is applied in exactly one database transaction
 * by {@link BetProcessingService#process(Bet)}; failures are classified by the container's error handler.
 */
@Component
public class BetEventListener {

    static final String MDC_BET_ID = "betId";
    static final String MDC_JACKPOT_ID = "jackpotId";

    private static final Logger log = LoggerFactory.getLogger(BetEventListener.class);

    private final BetEventMapper betEventMapper;
    private final BetProcessingService processingService;

    public BetEventListener(BetEventMapper betEventMapper, BetProcessingService processingService) {
        this.betEventMapper = betEventMapper;
        this.processingService = processingService;
    }

    /**
     * Processes one bet record.
     *
     * @param consumerRecord the record; a {@code null} value (tombstone) is skipped
     */
    @KafkaListener(topics = "${jackpot.kafka.bets-topic}")
    public void onBetPlaced(ConsumerRecord<String, BetPlacedEvent> consumerRecord) {
        BetPlacedEvent event = consumerRecord.value();
        if (event == null) {
            log.warn("Skipping tombstone record {}-{}@{} (key={})", consumerRecord.topic(), consumerRecord.partition(),
                    consumerRecord.offset(), consumerRecord.key());
            return;
        }
        MDC.put(MDC_BET_ID, event.betId());
        MDC.put(MDC_JACKPOT_ID, event.jackpotId());
        try {
            process(betEventMapper.toBet(event));
        } finally {
            MDC.remove(MDC_BET_ID);
            MDC.remove(MDC_JACKPOT_ID);
        }
    }

    private void process(Bet bet) {
        try {
            ProcessingResult result = processingService.process(bet);
            log.debug("Bet {} consumed with status {}", bet.betId(), result.status());
        } catch (DataIntegrityViolationException e) {
            if (!processingService.isProcessed(bet.betId())) {
                throw e;
            }
            log.warn("Bet {} was processed concurrently by another consumer (unique key race); treated as duplicate",
                    bet.betId());
        }
    }
}
