package com.sporty.jackpot.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessingResult;
import com.sporty.jackpot.domain.model.ProcessingStatus;
import com.sporty.jackpot.exception.InvalidBetException;
import com.sporty.jackpot.service.BetProcessingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("BetEventListener")
class BetEventListenerTest {

    private static final String TOPIC = "jackpot-bets";
    private static final int PARTITION = 4;
    private static final long OFFSET = 42L;
    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:30.123456Z");
    private static final BetPlacedEvent EVENT =
            new BetPlacedEvent("bet-1", "user-1", "jackpot-1", new BigDecimal("12.5"), PLACED_AT);
    /** The bet the listener must hand to the processing service for {@link #EVENT} (amount normalized). */
    private static final Bet EXPECTED_BET = new Bet("bet-1", "user-1", "jackpot-1", new BigDecimal("12.50"), PLACED_AT);

    private final BetProcessingService processingService = mock(BetProcessingService.class);
    private final BetEventListener listener = new BetEventListener(new BetEventMapper(), processingService);

    private final Logger listenerLogger = (Logger) LoggerFactory.getLogger(BetEventListener.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        MDC.clear();
        originalLevel = listenerLogger.getLevel();
        listenerLogger.setLevel(Level.DEBUG);
        logs.start();
        listenerLogger.addAppender(logs);
    }

    @AfterEach
    void restoreLogging() {
        listenerLogger.detachAppender(logs);
        listenerLogger.setLevel(originalLevel);
        MDC.clear();
    }

    @Test
    @DisplayName("a tombstone (null value) is skipped with a WARN and never reaches the processing service")
    void tombstoneIsSkipped() {
        listener.onBetPlaced(record(null));

        verifyNoInteractions(processingService);
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("tombstone")
                    .contains(TOPIC + "-" + PARTITION + "@" + OFFSET)
                    .contains("key=jackpot-1");
        });
        assertMdcCleared();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ProcessingStatus.class)
    @DisplayName("every processing outcome is acknowledged: bet mapped, processed once, logged, MDC set and cleared")
    void processingOutcomeIsAcknowledged(ProcessingStatus status) {
        Map<String, String> mdcDuringProcessing = new HashMap<>();
        when(processingService.process(EXPECTED_BET)).thenAnswer(invocation -> {
            mdcDuringProcessing.putAll(MDC.getCopyOfContextMap());
            return resultWith(status);
        });

        assertThatCode(() -> listener.onBetPlaced(record(EVENT))).doesNotThrowAnyException();

        verify(processingService).process(EXPECTED_BET);
        verify(processingService, never()).isProcessed(anyString());
        assertThat(mdcDuringProcessing)
                .containsEntry(BetEventListener.MDC_BET_ID, "bet-1")
                .containsEntry(BetEventListener.MDC_JACKPOT_ID, "jackpot-1");
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).isEqualTo("Bet bet-1 consumed with status " + status);
            assertThat(event.getMDCPropertyMap())
                    .containsEntry("betId", "bet-1")
                    .containsEntry("jackpotId", "jackpot-1");
        });
        assertMdcCleared();
    }

    @Test
    @DisplayName("a unique-key race lost to another consumer that committed the bet is swallowed as a duplicate")
    void dataIntegrityViolationOfAlreadyProcessedBetIsTreatedAsDuplicate() {
        when(processingService.process(EXPECTED_BET))
                .thenThrow(new DataIntegrityViolationException("duplicate key pk_bet"));
        when(processingService.isProcessed("bet-1")).thenReturn(true);

        assertThatCode(() -> listener.onBetPlaced(record(EVENT))).doesNotThrowAnyException();

        verify(processingService).isProcessed("bet-1");
        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Bet bet-1 was processed concurrently by another consumer (unique key race); "
                            + "treated as duplicate");
            assertThat(event.getMDCPropertyMap())
                    .containsEntry("betId", "bet-1")
                    .containsEntry("jackpotId", "jackpot-1");
        });
        assertMdcCleared();
    }

    @Test
    @DisplayName("a data integrity violation for a bet that is still not stored is rethrown unchanged (dead-lettered)")
    void dataIntegrityViolationOfUnprocessedBetIsRethrown() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("check constraint violated");
        when(processingService.process(EXPECTED_BET)).thenThrow(violation);
        when(processingService.isProcessed("bet-1")).thenReturn(false);

        assertThatThrownBy(() -> listener.onBetPlaced(record(EVENT))).isSameAs(violation);

        verify(processingService).isProcessed("bet-1");
        assertThat(logs.list).isEmpty();
        assertMdcCleared();
    }

    @Test
    @DisplayName("an invalid payload fails with InvalidBetException before any processing and the MDC is cleared")
    void invalidPayloadPropagates() {
        BetPlacedEvent invalid = new BetPlacedEvent("bet-9", "user-9", "jackpot-9", new BigDecimal("-5.00"), PLACED_AT);

        assertThatThrownBy(() -> listener.onBetPlaced(record(invalid)))
                .isInstanceOf(InvalidBetException.class)
                .hasMessageContaining("amount must be positive");

        verifyNoInteractions(processingService);
        assertMdcCleared();
    }

    @Test
    @DisplayName("a payload without a bet id is rejected as an invalid bet, not a NullPointerException")
    void payloadWithoutBetIdPropagatesInvalidBet() {
        BetPlacedEvent invalid = new BetPlacedEvent(null, "user-9", null, new BigDecimal("5.00"), PLACED_AT);

        assertThatThrownBy(() -> listener.onBetPlaced(record(invalid)))
                .isInstanceOf(InvalidBetException.class)
                .hasMessageContaining("betId");

        verifyNoInteractions(processingService);
        assertMdcCleared();
    }

    @Test
    @DisplayName("any other processing failure propagates unchanged to the error handler without a duplicate check")
    void otherFailuresPropagate() {
        CannotAcquireLockException lockTimeout = new CannotAcquireLockException("lock timeout on jackpot row");
        when(processingService.process(any(Bet.class))).thenThrow(lockTimeout);

        assertThatThrownBy(() -> listener.onBetPlaced(record(EVENT))).isSameAs(lockTimeout);

        verify(processingService, never()).isProcessed(anyString());
        assertMdcCleared();
    }

    @Test
    @DisplayName("only the listener's own MDC keys are removed; entries of the calling thread survive")
    void foreignMdcEntriesSurvive() {
        MDC.put("traceId", "trace-7");
        when(processingService.process(EXPECTED_BET)).thenReturn(ProcessingResult.duplicate("bet-1"));

        listener.onBetPlaced(record(EVENT));

        assertThat(MDC.get("traceId")).isEqualTo("trace-7");
        assertMdcCleared();
    }

    private static ConsumerRecord<String, BetPlacedEvent> record(BetPlacedEvent value) {
        return new ConsumerRecord<>(TOPIC, PARTITION, OFFSET, "jackpot-1", value);
    }

    private static ProcessingResult resultWith(ProcessingStatus status) {
        return switch (status) {
            case PROCESSED -> ProcessingResult.processed(
                    new Contribution("bet-1", "user-1", "jackpot-1", new BigDecimal("12.50"), new BigDecimal("0.63"),
                            new BigDecimal("1000.63"), 1L, PLACED_AT),
                    new BetEvaluation("bet-1", "user-1", "jackpot-1", EvaluationOutcome.LOST,
                            new BigDecimal("1.0000"), new BigDecimal("0.00"), 1L, PLACED_AT));
            case DUPLICATE -> ProcessingResult.duplicate("bet-1");
            case NO_MATCHING_JACKPOT -> ProcessingResult.noMatchingJackpot("bet-1");
        };
    }

    private static void assertMdcCleared() {
        assertThat(MDC.get(BetEventListener.MDC_BET_ID)).isNull();
        assertThat(MDC.get(BetEventListener.MDC_JACKPOT_ID)).isNull();
    }
}
