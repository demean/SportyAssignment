package com.sporty.jackpot.integration;

import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessingResult;
import com.sporty.jackpot.domain.model.ProcessingStatus;
import com.sporty.jackpot.messaging.BetPlacedEvent;
import com.sporty.jackpot.support.TestJackpots;
import io.micrometer.core.instrument.Counter;
import java.math.BigDecimal;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Each bet is applied in exactly one transaction (DESIGN.md §1.2, T6, T7, T18): when the last write fails, the pool
 * update, the bet and its contribution are all rolled back and nothing is reported. The failure is provoked with the
 * double-payout backstop: cycle 1 of a 100 % jackpot is already paid out, so the payout insert violates
 * {@code UNIQUE(jackpot_id, pool_cycle)} after the pool was increased, the pool reset and the bet and contribution
 * were inserted.
 */
@DisplayName("Bet processing is one transaction (H2, no Kafka)")
class BetProcessingRollbackTest extends AbstractProcessingServiceIntegrationTest {

    private String jackpotId;
    private String fixtureBetId;

    @BeforeEach
    void createJackpotWhoseFirstCycleIsAlreadyPaidOut() {
        jackpotId = jackpots.create("100.00", fixedContribution("10.0"), fixedChance("100"));
        fixtureBetId = TestJackpots.uniqueId("fixture-bet");
        ledger.insertPaidOutCycle(jackpotId, 1, fixtureBetId);
    }

    @Test
    @DisplayName("a failing payout insert rolls back bet, contribution and pool change; no metric is emitted")
    void failingPayoutRollsBackTheWholeBet() {
        Bet bet = bet(TestJackpots.uniqueId("bet"));
        double processedBefore = processedCount();

        assertThatThrownBy(() -> processingService.process(bet))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContainingAll("uk_jackpot_reward_cycle");

        assertNothingOfTheBetWasKept(bet.betId());
        assertThat(processedCount()).as("AFTER_COMMIT metrics never see rolled-back work").isEqualTo(processedBefore);
    }

    @Test
    @DisplayName("the rolled-back bet leaves no trace: once the conflict is gone, the same bet is processed normally")
    void rolledBackBetIsProcessedNormallyOnRetry() {
        Bet bet = bet(TestJackpots.uniqueId("bet"));
        assertThatThrownBy(() -> processingService.process(bet)).isInstanceOf(DataIntegrityViolationException.class);
        ledger.deletePaidOutReward(fixtureBetId);

        ProcessingResult result = processingService.process(bet);

        assertThat(result.status()).as("not a DUPLICATE: the failed attempt stored nothing")
                .isEqualTo(ProcessingStatus.PROCESSED);
        assertThat(result.evaluation().outcome()).isEqualTo(EvaluationOutcome.WON);
        assertThat(result.evaluation().rewardAmount()).isEqualByComparingTo("105.00");
        assertThat(result.evaluation().jackpotCycle()).isEqualTo(1);
        JackpotLedger.JackpotRow jackpot = ledger.jackpot(jackpotId);
        assertThat(jackpot.currentPool()).isEqualByComparingTo("100.00");
        assertThat(jackpot.cycle()).isEqualTo(2);
        assertThat(jackpot.version()).as("contribution + payout of the one committed transaction").isEqualTo(2);
    }

    @Test
    @DisplayName("the listener rethrows a non-duplicate integrity violation (dead-lettered, not acked as duplicate)")
    void listenerRethrowsIntegrityViolationOfAnUnprocessedBet() {
        String betId = TestJackpots.uniqueId("bet");
        BetPlacedEvent event = new BetPlacedEvent(betId, "user-1", jackpotId, new BigDecimal("50.00"),
                clock.instant());

        assertThatThrownBy(() -> betEventListener.onBetPlaced(
                new ConsumerRecord<>("jackpot-bets", 0, 0L, jackpotId, event)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertNothingOfTheBetWasKept(betId);
    }

    private void assertNothingOfTheBetWasKept(String betId) {
        assertThat(processingService.isProcessed(betId)).isFalse();
        assertThat(ledger.rowsOfBet("bet", betId)).isZero();
        assertThat(ledger.rowsOfBet("jackpot_contribution", betId)).isZero();
        assertThat(ledger.rowsOfBet("jackpot_reward", betId)).isZero();
        assertThat(ledger.rowsOfBet("bet_evaluation", betId)).isZero();
        JackpotLedger.JackpotRow jackpot = ledger.jackpot(jackpotId);
        assertThat(jackpot.currentPool()).as("pool increase and reset rolled back").isEqualByComparingTo("100.00");
        assertThat(jackpot.cycle()).as("cycle increment rolled back").isEqualTo(1);
        assertThat(jackpot.version()).as("no committed jackpot update").isZero();
    }

    private Bet bet(String betId) {
        return new Bet(betId, "user-1", jackpotId, new BigDecimal("50.00"), clock.instant());
    }

    /** @return {@code jackpot.bets.processed} summed over all statuses */
    private double processedCount() {
        return meterRegistry.find("jackpot.bets.processed").counters().stream().mapToDouble(Counter::count).sum();
    }
}
