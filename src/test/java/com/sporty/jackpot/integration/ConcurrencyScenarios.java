package com.sporty.jackpot.integration;

import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.ProcessingResult;
import com.sporty.jackpot.domain.model.ProcessingStatus;
import com.sporty.jackpot.integration.ConcurrentRunner.Attempt;
import com.sporty.jackpot.messaging.BetEventListener;
import com.sporty.jackpot.messaging.BetPlacedEvent;
import com.sporty.jackpot.service.BetProcessingService;
import com.sporty.jackpot.support.TestJackpots;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The service-level concurrency scenarios of DESIGN.md §10.1, run against a real database: {@value #THREADS}
 * platform threads released together call {@link BetProcessingService#process(Bet)} directly, each call in its own
 * transaction. Shared by the H2 test ({@code BetProcessingConcurrencyTest}) and the PostgreSQL test
 * ({@code JackpotFlowIT}). The context must use a {@link ScriptedRandomGenerator} and nothing else may process bets
 * while a scenario runs (exact metric deltas are asserted).
 */
final class ConcurrencyScenarios {

    static final int THREADS = 32;

    /**
     * Test property for every context that runs these scenarios: {@value #THREADS} threads share a pool of 10
     * connections and serialize on one jackpot row lock, so waiting for a pooled connection can take as long as the
     * scenario itself; the production pool-acquire timeout (2 s) would turn that queueing into spurious
     * {@code CannotCreateTransactionException}s on a slow database (seen on PostgreSQL in a 2-CPU Docker VM).
     */
    static final String POOL_ACQUIRE_TIMEOUT_PROPERTY = "spring.datasource.hikari.connection-timeout=60000";

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyScenarios.class);
    private static final String PROCESSED_COUNTER = "jackpot.bets.processed";
    private static final String REWARDS_SUMMARY = "jackpot.rewards.amount";

    private final BetProcessingService processingService;
    private final BetEventListener betEventListener;
    private final TestJackpots jackpots;
    private final JackpotLedger ledger;
    private final ScriptedRandomGenerator random;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    ConcurrencyScenarios(BetProcessingService processingService, BetEventListener betEventListener,
                         TestJackpots jackpots, JackpotLedger ledger, ScriptedRandomGenerator random,
                         MeterRegistry meterRegistry, Clock clock) {
        this.processingService = processingService;
        this.betEventListener = betEventListener;
        this.jackpots = jackpots;
        this.ledger = ledger;
        this.random = random;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /**
     * (a) {@code THREADS × betsPerThread} bets on one jackpot (10 % contribution, 50 % chance, every
     * {@code winEvery}-th draw wins): no lost update, exactly one reward per cycle, version = number of bets.
     */
    void parallelContributionsKeepThePoolConsistent(int betsPerThread, int winEvery) {
        String jackpotId = jackpots.create("1000.00", fixedContribution("10.0"), fixedChance("50"));
        int bets = THREADS * betsPerThread;
        assertThat(bets % winEvery).as("bets must be a multiple of winEvery").isZero();
        Instant placedAt = clock.instant();
        List<Callable<ProcessingResult>> tasks = IntStream.range(0, bets)
                .mapToObj(i -> process(new Bet(TestJackpots.uniqueId("bet-a"), "user-" + i % 7, jackpotId,
                        BigDecimal.valueOf(1000 + 37L * i, 2), placedAt)))
                .toList();
        double processedBefore = processedCount(ProcessingStatus.PROCESSED);
        random.winEvery(winEvery);

        List<ProcessingResult> results = successfulResults(ConcurrentRunner.run(THREADS, tasks));

        long wins = bets / winEvery;
        assertThat(results).extracting(ProcessingResult::status).containsOnly(ProcessingStatus.PROCESSED);
        assertThat(random.draws()).as("every contributing bet is drawn exactly once").isEqualTo(bets);
        assertThat(results).filteredOn(result -> result.evaluation().won()).hasSize((int) wins);
        JackpotLedger.Snapshot snapshot = ledger.assertConsistent(jackpotId);
        assertThat(snapshot.contributions()).hasSize(bets);
        assertThat(snapshot.rewards()).hasSize((int) wins);
        assertThat(snapshot.jackpot().cycle()).isEqualTo(wins + 1);
        assertThat(ledger.evaluationCycles(jackpotId, "WON"))
                .containsExactlyElementsOf(LongStream.rangeClosed(1, wins).boxed().toList());
        assertThat(ledger.evaluationCycles(jackpotId, "LOST")).hasSize(bets - (int) wins);
        assertThat(processedCount(ProcessingStatus.PROCESSED) - processedBefore).isEqualTo(bets);
    }

    /**
     * (b) the same bet id sent concurrently to two jackpots (half of the threads each): exactly one bet row, every
     * loser is turned away as {@code DUPLICATE} or by the primary key (and then {@code isProcessed} confirms the
     * duplicate, as the listener does), the losing jackpot is untouched and nothing is counted twice.
     */
    void sameBetIdOnTwoJackpotsIsProcessedOnce() {
        String jackpotA = jackpots.create("1000.00", fixedContribution("10.0"), fixedChance("0"));
        String jackpotB = jackpots.create("1000.00", fixedContribution("10.0"), fixedChance("0"));
        String betId = TestJackpots.uniqueId("race-bet");
        Instant placedAt = clock.instant();
        List<Callable<ProcessingResult>> tasks = IntStream.range(0, THREADS)
                .mapToObj(i -> process(new Bet(betId, "race-user", i % 2 == 0 ? jackpotA : jackpotB,
                        new BigDecimal("100.00"), placedAt)))
                .toList();
        double processedBefore = processedCount(ProcessingStatus.PROCESSED);

        List<Attempt<ProcessingResult>> attempts = ConcurrentRunner.run(THREADS, tasks);

        assertThat(attempts).filteredOn(attempt -> !attempt.succeeded())
                .allSatisfy(attempt -> assertThat(attempt.failure())
                        .as("a losing call may only fail on the primary key")
                        .isInstanceOf(DataIntegrityViolationException.class));
        List<Attempt<ProcessingResult>> winners = attempts.stream()
                .filter(attempt -> attempt.succeeded() && attempt.result().status() == ProcessingStatus.PROCESSED)
                .toList();
        assertThat(winners).as("exactly one call processes the bet").hasSize(1);
        long duplicates = attempts.stream()
                .filter(attempt -> attempt.succeeded() && attempt.result().status() == ProcessingStatus.DUPLICATE)
                .count();
        long integrityViolations = attempts.stream().filter(attempt -> !attempt.succeeded()).count();
        assertThat(duplicates + integrityViolations).isEqualTo(THREADS - 1);

        String winner = winners.getFirst().index() % 2 == 0 ? jackpotA : jackpotB;
        String loser = winner.equals(jackpotA) ? jackpotB : jackpotA;
        assertOneBetProcessedOn(betId, winner, loser);
        if (integrityViolations > 0) {
            assertThat(processingService.isProcessed(betId))
                    .as("a primary-key loser is confirmed as duplicate by isProcessed").isTrue();
        }
        assertThat(processedCount(ProcessingStatus.PROCESSED) - processedBefore).isEqualTo(1);
        assertThat(processedCount(ProcessingStatus.DUPLICATE)).as("duplicates are never counted").isZero();
        log.info("Same-bet-id race: {} loser(s) saw the committed bet (DUPLICATE), {} hit the primary key",
                duplicates, integrityViolations);
    }

    /**
     * (b) through the Kafka listener: the same record for two jackpots consumed concurrently; the listener resolves
     * every loser (including a primary-key race) as a duplicate, so no call fails and the record would be acked.
     */
    void sameBetIdRacingThroughTheListenerIsAckedOnce() {
        String jackpotA = jackpots.create("1000.00", fixedContribution("10.0"), fixedChance("0"));
        String jackpotB = jackpots.create("1000.00", fixedContribution("10.0"), fixedChance("0"));
        String betId = TestJackpots.uniqueId("race-bet");
        Instant placedAt = clock.instant();
        List<Callable<Void>> tasks = IntStream.range(0, THREADS)
                .mapToObj(i -> consume(i, new BetPlacedEvent(betId, "race-user", i % 2 == 0 ? jackpotA : jackpotB,
                        new BigDecimal("100.00"), placedAt)))
                .toList();

        List<Attempt<Void>> attempts = ConcurrentRunner.run(THREADS, tasks);

        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.failure())
                .as("the listener resolves every loser as a duplicate").isNull());
        String winner = ledger.jackpotOfBet(betId);
        assertOneBetProcessedOn(betId, winner, winner.equals(jackpotA) ? jackpotB : jackpotA);
    }

    /**
     * (c) {@value #THREADS} parallel bets on a 100 % jackpot: every bet wins in its own cycle, the pool is reset
     * after each payout and ends at its initial value.
     */
    void everyParallelBetWinsACertainJackpotInItsOwnCycle() {
        random.reset();
        String jackpotId = jackpots.create("100.00", fixedContribution("10.0"), fixedChance("100"));
        Instant placedAt = clock.instant();
        List<Callable<ProcessingResult>> tasks = IntStream.range(0, THREADS)
                .mapToObj(i -> process(new Bet(TestJackpots.uniqueId("bet-c"), "user-" + i, jackpotId,
                        new BigDecimal("50.00"), placedAt)))
                .toList();
        DistributionSummary rewards = meterRegistry.get(REWARDS_SUMMARY).summary();
        long rewardsBefore = rewards.count();
        double paidBefore = rewards.totalAmount();

        List<ProcessingResult> results = successfulResults(ConcurrentRunner.run(THREADS, tasks));

        List<Long> cycles = LongStream.rangeClosed(1, THREADS).boxed().toList();
        assertThat(results).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED);
            assertThat(result.evaluation().won()).isTrue();
            assertThat(result.evaluation().rewardAmount()).isEqualByComparingTo("105.00");
            assertThat(result.contribution().currentJackpotAmount()).isEqualByComparingTo("105.00");
        });
        assertThat(results).extracting(result -> result.evaluation().jackpotCycle())
                .as("every win has its own cycle").containsExactlyInAnyOrderElementsOf(cycles);
        JackpotLedger.Snapshot snapshot = ledger.assertConsistent(jackpotId);
        assertThat(snapshot.jackpot().currentPool()).isEqualByComparingTo("100.00");
        assertThat(snapshot.jackpot().cycle()).isEqualTo(THREADS + 1);
        assertThat(snapshot.rewards()).hasSize(THREADS)
                .allSatisfy(reward -> assertThat(reward.rewardAmount()).isEqualByComparingTo("105.00"));
        assertThat(ledger.evaluationCycles(jackpotId, "WON")).containsExactlyElementsOf(cycles);
        assertThat(rewards.count() - rewardsBefore).isEqualTo(THREADS);
        // the summary is a context-wide double: exact equality would depend on what earlier scenarios paid out
        assertThat(rewards.totalAmount() - paidBefore).isCloseTo(105.0 * THREADS, within(1e-6));
    }

    private void assertOneBetProcessedOn(String betId, String winner, String loser) {
        assertThat(ledger.rowsOfBet("bet", betId)).as("exactly one bet row").isOne();
        assertThat(ledger.jackpotOfBet(betId)).isEqualTo(winner);
        assertThat(ledger.rowsOfBet("jackpot_contribution", betId)).isOne();
        assertThat(ledger.rowsOfBet("bet_evaluation", betId)).isOne();
        JackpotLedger.Snapshot won = ledger.assertConsistent(winner);
        assertThat(won.jackpot().currentPool()).isEqualByComparingTo("1010.00");
        assertThat(won.jackpot().version()).isOne();
        JackpotLedger.Snapshot lost = ledger.assertConsistent(loser);
        assertThat(lost.contributions()).as("the losing jackpot got no contribution").isEmpty();
        assertThat(lost.jackpot().currentPool()).as("the losing jackpot's pool is unchanged")
                .isEqualByComparingTo("1000.00");
        assertThat(lost.jackpot().version()).isZero();
    }

    private Callable<ProcessingResult> process(Bet bet) {
        return () -> processingService.process(bet);
    }

    private Callable<Void> consume(int offset, BetPlacedEvent event) {
        return () -> {
            betEventListener.onBetPlaced(new ConsumerRecord<>("jackpot-bets", 0, offset, event.jackpotId(), event));
            return null;
        };
    }

    private static List<ProcessingResult> successfulResults(List<Attempt<ProcessingResult>> attempts) {
        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.failure())
                .as("bet #%d must be processed without error", attempt.index()).isNull());
        return attempts.stream().map(Attempt::result).toList();
    }

    private double processedCount(ProcessingStatus status) {
        Counter counter = meterRegistry.find(PROCESSED_COUNTER).tag("status", status.name()).counter();
        return counter == null ? 0 : counter.count();
    }
}
