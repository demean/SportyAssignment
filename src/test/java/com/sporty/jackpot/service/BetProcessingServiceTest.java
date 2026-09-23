package com.sporty.jackpot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessingResult;
import com.sporty.jackpot.domain.model.ProcessingStatus;
import com.sporty.jackpot.domain.policy.ContributionPolicy;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.RewardPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import com.sporty.jackpot.persistence.entity.BetEntity;
import com.sporty.jackpot.persistence.entity.BetEvaluationEntity;
import com.sporty.jackpot.persistence.entity.JackpotContributionEntity;
import com.sporty.jackpot.persistence.entity.JackpotEntity;
import com.sporty.jackpot.persistence.entity.JackpotRewardEntity;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.persistence.repository.BetEvaluationRepository;
import com.sporty.jackpot.persistence.repository.BetRepository;
import com.sporty.jackpot.persistence.repository.JackpotContributionRepository;
import com.sporty.jackpot.persistence.repository.JackpotRepository;
import com.sporty.jackpot.persistence.repository.JackpotRewardRepository;
import com.sporty.jackpot.service.event.BetProcessedEvent;
import com.sporty.jackpot.support.StubRandomGenerator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@DisplayName("BetProcessingService")
class BetProcessingServiceTest {

    /** Sub-microsecond digits: the service must pass the clock's instant through unchanged. */
    private static final Instant NOW = Instant.parse("2026-09-23T10:15:30.123456789Z");
    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:29.987654321Z");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String BET_ID = "bet-1";
    private static final String USER_ID = "user-1";
    private static final String JACKPOT_ID = "jackpot-1";

    @Mock
    private JackpotRepository jackpotRepository;
    @Mock
    private BetRepository betRepository;
    @Mock
    private JackpotContributionRepository contributionRepository;
    @Mock
    private JackpotRewardRepository rewardRepository;
    @Mock
    private BetEvaluationRepository evaluationRepository;
    @Mock
    private RewardDraw rewardDraw;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<BetEntity> savedBet;
    @Captor
    private ArgumentCaptor<JackpotRewardEntity> savedReward;
    @Captor
    private ArgumentCaptor<BigDecimal> drawnChance;

    private BetProcessingService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(rewardDraw);
    }

    private BetProcessingService serviceWith(RewardDraw draw) {
        return new BetProcessingService(jackpotRepository, betRepository, contributionRepository, rewardRepository,
                evaluationRepository, new EntityMapper(), draw, eventPublisher, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Bet bet(String amount) {
        return new Bet(BET_ID, USER_ID, JACKPOT_ID, new BigDecimal(amount), PLACED_AT);
    }

    private static JackpotEntity jackpot(String initialPool, String currentPool, long cycle,
                                         ContributionPolicy contributionPolicy, RewardPolicy rewardPolicy) {
        return new JackpotEntity(JACKPOT_ID, "Test jackpot", new BigDecimal(initialPool), new BigDecimal(currentPool),
                contributionPolicy, rewardPolicy, cycle, CREATED_AT);
    }

    private static FixedContributionPolicy fixedContribution(String percentage) {
        return new FixedContributionPolicy(new BigDecimal(percentage));
    }

    private static FixedChanceRewardPolicy fixedChance(String percentage) {
        return new FixedChanceRewardPolicy(new BigDecimal(percentage));
    }

    /** A new bet id on an existing jackpot; contribution and evaluation saves return the saved entity. */
    private void givenNewBetOn(JackpotEntity jackpot) {
        when(jackpotRepository.findByIdForUpdate(JACKPOT_ID)).thenReturn(Optional.of(jackpot));
        when(betRepository.findById(BET_ID)).thenReturn(Optional.empty());
        when(contributionRepository.saveAndFlush(any(JackpotContributionEntity.class))).then(returnsFirstArg());
        when(evaluationRepository.saveAndFlush(any(BetEvaluationEntity.class))).then(returnsFirstArg());
    }

    private void assertContributedBetSaved(String amount) {
        verify(betRepository).saveAndFlush(savedBet.capture());
        BetEntity bet = savedBet.getValue();
        assertThat(bet.isNew()).as("a new bet is always INSERTed (T3)").isTrue();
        assertThat(bet.getId()).isEqualTo(BET_ID);
        assertThat(bet.getUserId()).isEqualTo(USER_ID);
        assertThat(bet.getJackpotId()).isEqualTo(JACKPOT_ID);
        assertThat(bet.getBetAmount()).isEqualByComparingTo(amount);
        assertThat(bet.getStatus()).isEqualTo(BetStatus.CONTRIBUTED);
        assertThat(bet.getPlacedAt()).isEqualTo(PLACED_AT);
        assertThat(bet.getProcessedAt()).isEqualTo(NOW);
    }

    private static void assertContribution(Contribution contribution, String stake, String amount, String poolAfter,
                                           long cycle) {
        assertThat(contribution.betId()).isEqualTo(BET_ID);
        assertThat(contribution.userId()).isEqualTo(USER_ID);
        assertThat(contribution.jackpotId()).isEqualTo(JACKPOT_ID);
        assertThat(contribution.stakeAmount()).isEqualByComparingTo(stake);
        assertThat(contribution.contributionAmount()).isEqualByComparingTo(amount);
        assertThat(contribution.currentJackpotAmount()).isEqualByComparingTo(poolAfter);
        assertThat(contribution.jackpotCycle()).isEqualTo(cycle);
        assertThat(contribution.createdAt()).isEqualTo(NOW);
    }

    private static void assertEvaluation(BetEvaluation evaluation, EvaluationOutcome outcome, String chance,
                                         String reward, long cycle) {
        assertThat(evaluation.betId()).isEqualTo(BET_ID);
        assertThat(evaluation.userId()).isEqualTo(USER_ID);
        assertThat(evaluation.jackpotId()).isEqualTo(JACKPOT_ID);
        assertThat(evaluation.outcome()).isEqualTo(outcome);
        assertThat(evaluation.winChancePercentage()).isEqualByComparingTo(chance).hasScaleOf(4);
        assertThat(evaluation.rewardAmount()).isEqualByComparingTo(reward).hasScaleOf(2);
        assertThat(evaluation.jackpotCycle()).isEqualTo(cycle);
        assertThat(evaluation.evaluatedAt()).isEqualTo(NOW);
    }

    private BetProcessedEvent publishedEvent() {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(BetProcessedEvent.class);
        return (BetProcessedEvent) event.getValue();
    }

    @Nested
    @DisplayName("bet for an unknown jackpot")
    class UnknownJackpot {

        @Test
        @DisplayName("is stored as NO_MATCHING_JACKPOT without contribution or evaluation, and an event is published")
        void storesBetAsNoMatchingJackpot() {
            when(jackpotRepository.findByIdForUpdate(JACKPOT_ID)).thenReturn(Optional.empty());
            when(betRepository.findById(BET_ID)).thenReturn(Optional.empty());

            ProcessingResult result = service.process(bet("25.50"));

            assertThat(result).isEqualTo(ProcessingResult.noMatchingJackpot(BET_ID));
            assertThat(result.status()).isEqualTo(ProcessingStatus.NO_MATCHING_JACKPOT);
            assertThat(result.contribution()).isNull();
            assertThat(result.evaluation()).isNull();

            InOrder order = inOrder(jackpotRepository, betRepository, eventPublisher);
            order.verify(jackpotRepository).findByIdForUpdate(JACKPOT_ID);
            order.verify(betRepository).findById(BET_ID);
            order.verify(betRepository).saveAndFlush(savedBet.capture());
            order.verify(eventPublisher).publishEvent(new BetProcessedEvent(ProcessingStatus.NO_MATCHING_JACKPOT,
                    BET_ID, JACKPOT_ID, null, null, null, PLACED_AT, NOW));

            BetEntity bet = savedBet.getValue();
            assertThat(bet.isNew()).isTrue();
            assertThat(bet.getId()).isEqualTo(BET_ID);
            assertThat(bet.getUserId()).isEqualTo(USER_ID);
            assertThat(bet.getJackpotId()).isEqualTo(JACKPOT_ID);
            assertThat(bet.getBetAmount()).isEqualByComparingTo("25.50");
            assertThat(bet.getStatus()).isEqualTo(BetStatus.NO_MATCHING_JACKPOT);
            assertThat(bet.getPlacedAt()).isEqualTo(PLACED_AT);
            assertThat(bet.getProcessedAt()).isEqualTo(NOW);
            verifyNoInteractions(contributionRepository, rewardRepository, evaluationRepository, rewardDraw);
        }
    }

    @Nested
    @DisplayName("duplicate bet id")
    class DuplicateBet {

        private final JackpotEntity jackpot = jackpot("1000.00", "1234.56", 2, fixedContribution("5"),
                fixedChance("100"));

        private void givenStoredBet(String userId, String jackpotId, String amount, Optional<JackpotEntity> locked) {
            when(jackpotRepository.findByIdForUpdate(JACKPOT_ID)).thenReturn(locked);
            when(betRepository.findById(BET_ID)).thenReturn(Optional.of(new BetEntity(BET_ID, userId, jackpotId,
                    new BigDecimal(amount), BetStatus.CONTRIBUTED, PLACED_AT.minusSeconds(60), NOW.minusSeconds(59))));
        }

        private void assertNothingChanged() {
            verify(betRepository, never()).saveAndFlush(any());
            verifyNoInteractions(contributionRepository, rewardRepository, evaluationRepository, rewardDraw,
                    eventPublisher);
            assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("1234.56");
            assertThat(jackpot.getCycle()).isEqualTo(2);
            assertThat(jackpot.getUpdatedAt()).isEqualTo(CREATED_AT);
        }

        @Test
        @DisplayName("with the same payload is a no-op logged at INFO (amount compared with compareTo)")
        void samePayloadIsIgnored() {
            givenStoredBet(USER_ID, JACKPOT_ID, "25.5", Optional.of(jackpot));

            try (ServiceLogCapture logs = ServiceLogCapture.of(BetProcessingService.class)) {
                ProcessingResult result = service.process(bet("25.50"));

                assertThat(result).isEqualTo(ProcessingResult.duplicate(BET_ID));
                assertThat(logs.messages(Level.INFO))
                        .containsExactly("Duplicate bet bet-1 ignored (already processed)");
                assertThat(logs.messages(Level.WARN)).isEmpty();
            }
            InOrder order = inOrder(jackpotRepository, betRepository);
            order.verify(jackpotRepository).findByIdForUpdate(JACKPOT_ID);
            order.verify(betRepository).findById(BET_ID);
            assertNothingChanged();
        }

        @ParameterizedTest(name = "stored userId={0} jackpotId={1} amount={2} -> WARN")
        @CsvSource({
                "user-2, jackpot-1, 25.50",
                "user-1, jackpot-2, 25.50",
                "user-1, jackpot-1, 25.51"
        })
        @DisplayName("with a different payload is a no-op logged at WARN with both payloads")
        void differentPayloadIsIgnoredWithWarning(String storedUserId, String storedJackpotId, String storedAmount) {
            givenStoredBet(storedUserId, storedJackpotId, storedAmount, Optional.of(jackpot));

            try (ServiceLogCapture logs = ServiceLogCapture.of(BetProcessingService.class)) {
                ProcessingResult result = service.process(bet("25.50"));

                assertThat(result).isEqualTo(ProcessingResult.duplicate(BET_ID));
                assertThat(logs.messages(Level.WARN)).containsExactly("Duplicate bet bet-1 ignored, but its payload "
                        + "differs from the processed bet: stored userId=" + storedUserId + " jackpotId="
                        + storedJackpotId + " amount=" + storedAmount
                        + ", received userId=user-1 jackpotId=jackpot-1 amount=25.50");
                assertThat(logs.messages(Level.INFO)).isEmpty();
            }
            assertNothingChanged();
        }

        @Test
        @DisplayName("is detected before the jackpot existence check (never re-stored as NO_MATCHING_JACKPOT)")
        void duplicateOfBetForUnknownJackpotIsIgnored() {
            givenStoredBet(USER_ID, JACKPOT_ID, "25.50", Optional.empty());

            ProcessingResult result = service.process(bet("25.50"));

            assertThat(result.status()).isEqualTo(ProcessingStatus.DUPLICATE);
            assertThat(result.betId()).isEqualTo(BET_ID);
            assertNothingChanged();
        }
    }

    @Nested
    @DisplayName("contribution")
    class ContributionCalculation {

        @Test
        @DisplayName("fixed policy contributes its percentage of the stake and grows the pool")
        void fixedPolicyContributesPercentageOfStake() {
            JackpotEntity jackpot = jackpot("1000.00", "1000.00", 1, fixedContribution("5"), fixedChance("1"));
            givenNewBetOn(jackpot);
            when(rewardDraw.isWinning(any())).thenReturn(false);

            ProcessingResult result = service.process(bet("250.00"));

            assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED);
            assertThat(result.betId()).isEqualTo(BET_ID);
            assertContribution(result.contribution(), "250.00", "12.50", "1012.50", 1);
            assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("1012.50");
            assertThat(jackpot.getUpdatedAt()).isEqualTo(NOW);
            assertContributedBetSaved("250.00");
        }

        @Test
        @DisplayName("variable policy uses the pool BEFORE the contribution")
        void variableContributionUsesPoolBeforeContribution() {
            // pool before = 200.00 -> one step above the initial 100.00 -> 20 - 1 = 19 % of 1000.00 = 190.00
            // (a percentage taken from the grown pool of 390.00 would be 17.1 % = 171.00)
            JackpotEntity jackpot = jackpot("100.00", "200.00", 1, new VariableContributionPolicy(
                    new BigDecimal("20"), new BigDecimal("5"), new BigDecimal("1"), new BigDecimal("100")),
                    fixedChance("1"));
            givenNewBetOn(jackpot);
            when(rewardDraw.isWinning(any())).thenReturn(false);

            ProcessingResult result = service.process(bet("1000.00"));

            assertContribution(result.contribution(), "1000.00", "190.00", "390.00", 1);
            assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("390.00");
        }
    }

    @Nested
    @DisplayName("evaluation")
    class Evaluation {

        @Test
        @DisplayName("win chance is computed on the pool AFTER the contribution")
        void winChanceUsesPoolAfterContribution() {
            // 10 % of 100.00 = 10.00 -> pool 110.00 = one step of 10 above 100.00 -> 5 + 10 = 15 %
            // (the pool before, 100.00, would give 5 %)
            JackpotEntity jackpot = jackpot("100.00", "100.00", 1, fixedContribution("10"),
                    new VariableChanceRewardPolicy(new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("10"),
                            new BigDecimal("1000")));
            givenNewBetOn(jackpot);
            when(rewardDraw.isWinning(any())).thenReturn(false);

            ProcessingResult result = service.process(bet("100.00"));

            verify(rewardDraw).isWinning(drawnChance.capture());
            assertThat(drawnChance.getValue()).isEqualByComparingTo("15.0000");
            assertContribution(result.contribution(), "100.00", "10.00", "110.00", 1);
            assertEvaluation(result.evaluation(), EvaluationOutcome.LOST, "15.0000", "0.00", 1);
        }

        @ParameterizedTest(name = "{0} % of {1} rounds to 0.00 -> LOST without a draw")
        @CsvSource({
                "0, 250.00",
                "5, 0.09",
                "1, 0.49"
        })
        @DisplayName("a zero contribution is LOST with chance 0 and never drawn, even on a 100 % jackpot")
        void zeroContributionIsLostWithoutDraw(String percentage, String stake) {
            JackpotEntity jackpot = jackpot("1000.00", "1000.00", 7, fixedContribution(percentage),
                    fixedChance("100"));
            givenNewBetOn(jackpot);

            ProcessingResult result = service.process(bet(stake));

            assertContribution(result.contribution(), stake, "0.00", "1000.00", 7);
            assertEvaluation(result.evaluation(), EvaluationOutcome.LOST, "0", "0", 7);
            assertThat(result.evaluation().won()).isFalse();
            verifyNoInteractions(rewardDraw, rewardRepository);
            assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("1000.00");
            assertThat(jackpot.getCycle()).isEqualTo(7);
            assertThat(publishedEvent()).isEqualTo(new BetProcessedEvent(ProcessingStatus.PROCESSED, BET_ID,
                    JACKPOT_ID, EvaluationOutcome.LOST, new BigDecimal("0.00"), new BigDecimal("0.00"), PLACED_AT,
                    NOW));
        }

        @Test
        @DisplayName("WON pays the whole pool, resets it, starts the next cycle and records the ORIGINAL cycle")
        void wonAwardsResetsAndIncrementsCycle() {
            JackpotEntity jackpot = jackpot("1000.00", "1000.00", 3, fixedContribution("5"), fixedChance("1"));
            givenNewBetOn(jackpot);
            when(rewardDraw.isWinning(any())).thenReturn(true);

            ProcessingResult result = service.process(bet("200.00"));

            verify(rewardDraw).isWinning(drawnChance.capture());
            assertThat(drawnChance.getValue()).isEqualByComparingTo("1.0000");
            assertThat(result.status()).isEqualTo(ProcessingStatus.PROCESSED);
            assertContribution(result.contribution(), "200.00", "10.00", "1010.00", 3);
            assertEvaluation(result.evaluation(), EvaluationOutcome.WON, "1.0000", "1010.00", 3);
            assertThat(result.evaluation().won()).isTrue();

            assertThat(jackpot.getCurrentPoolAmount()).as("pool reset to initial").isEqualByComparingTo("1000.00");
            assertThat(jackpot.getCycle()).as("next cycle").isEqualTo(4);
            assertThat(jackpot.getUpdatedAt()).isEqualTo(NOW);

            verify(rewardRepository).saveAndFlush(savedReward.capture());
            JackpotRewardEntity reward = savedReward.getValue();
            assertThat(reward.getBetId()).isEqualTo(BET_ID);
            assertThat(reward.getUserId()).isEqualTo(USER_ID);
            assertThat(reward.getJackpotId()).isEqualTo(JACKPOT_ID);
            assertThat(reward.getJackpotRewardAmount()).isEqualByComparingTo("1010.00");
            assertThat(reward.getPoolCycle()).as("cycle in which the pool was won").isEqualTo(3);
            assertThat(reward.getCreatedAt()).isEqualTo(NOW);

            assertContributedBetSaved("200.00");
            assertThat(publishedEvent()).isEqualTo(new BetProcessedEvent(ProcessingStatus.PROCESSED, BET_ID,
                    JACKPOT_ID, EvaluationOutcome.WON, new BigDecimal("10.00"), new BigDecimal("1010.00"), PLACED_AT,
                    NOW));
        }

        @Test
        @DisplayName("LOST keeps the grown pool and the cycle, and records no reward")
        void lostKeepsPoolAndCycle() {
            JackpotEntity jackpot = jackpot("1000.00", "1000.00", 3, fixedContribution("5"), fixedChance("1"));
            givenNewBetOn(jackpot);
            when(rewardDraw.isWinning(any())).thenReturn(false);

            ProcessingResult result = service.process(bet("200.00"));

            assertEvaluation(result.evaluation(), EvaluationOutcome.LOST, "1.0000", "0.00", 3);
            assertThat(result.evaluation().won()).isFalse();
            assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("1010.00");
            assertThat(jackpot.getCycle()).isEqualTo(3);
            verifyNoInteractions(rewardRepository);

            InOrder order = inOrder(betRepository, contributionRepository, evaluationRepository, eventPublisher);
            order.verify(betRepository).saveAndFlush(any(BetEntity.class));
            order.verify(contributionRepository).saveAndFlush(any(JackpotContributionEntity.class));
            order.verify(evaluationRepository).saveAndFlush(any(BetEvaluationEntity.class));
            order.verify(eventPublisher).publishEvent(new BetProcessedEvent(ProcessingStatus.PROCESSED, BET_ID,
                    JACKPOT_ID, EvaluationOutcome.LOST, new BigDecimal("10.00"), new BigDecimal("0.00"), PLACED_AT,
                    NOW));
        }

        @Test
        @DisplayName("WON writes under the lock in FK order: bet, contribution, reward, evaluation, then the event")
        void writesInForeignKeyOrder() {
            JackpotEntity jackpot = jackpot("1000.00", "1000.00", 1, fixedContribution("5"), fixedChance("1"));
            givenNewBetOn(jackpot);
            when(rewardDraw.isWinning(any())).thenReturn(true);

            service.process(bet("200.00"));

            InOrder order = inOrder(jackpotRepository, betRepository, contributionRepository, rewardRepository,
                    evaluationRepository, eventPublisher);
            order.verify(jackpotRepository).findByIdForUpdate(JACKPOT_ID);
            order.verify(betRepository).findById(BET_ID);
            order.verify(betRepository).saveAndFlush(any(BetEntity.class));
            order.verify(contributionRepository).saveAndFlush(any(JackpotContributionEntity.class));
            order.verify(rewardRepository).saveAndFlush(any(JackpotRewardEntity.class));
            order.verify(evaluationRepository).saveAndFlush(any(BetEvaluationEntity.class));
            order.verify(eventPublisher).publishEvent(any(BetProcessedEvent.class));
            verifyNoMoreInteractions(jackpotRepository, betRepository, contributionRepository, rewardRepository,
                    evaluationRepository, eventPublisher);
        }

        @Test
        @DisplayName("deterministic demo: 250.00 on a fresh lucky-like jackpot reaches the limit and wins 150.00")
        void luckyDemoWinsAtTheLimit() {
            StubRandomGenerator highestDraw = new StubRandomGenerator(RewardDraw.DRAW_RANGE - 1);
            BetProcessingService realDrawService = serviceWith(new RewardDraw(highestDraw));
            JackpotEntity jackpot = jackpot("100.00", "100.00", 1,
                    new VariableContributionPolicy(new BigDecimal("20.0"), new BigDecimal("5.0"),
                            new BigDecimal("1.0"), new BigDecimal("100")),
                    new VariableChanceRewardPolicy(new BigDecimal("5.0"), new BigDecimal("10.0"),
                            new BigDecimal("10"), new BigDecimal("150")));
            givenNewBetOn(jackpot);

            ProcessingResult result = realDrawService.process(bet("250.00"));

            assertContribution(result.contribution(), "250.00", "50.00", "150.00", 1);
            assertEvaluation(result.evaluation(), EvaluationOutcome.WON, "100.0000", "150.00", 1);
            assertThat(jackpot.getCurrentPoolAmount()).isEqualByComparingTo("100.00");
            assertThat(jackpot.getCycle()).isEqualTo(2);
            assertThat(highestDraw.remaining()).as("drawn exactly once").isZero();
        }
    }

    @Nested
    @DisplayName("concurrent duplicate (T3)")
    class ConcurrentDuplicate {

        @Test
        @DisplayName("a bet-id PK violation propagates to the caller; nothing else is written or published")
        void primaryKeyViolationPropagates() {
            JackpotEntity jackpot = jackpot("1000.00", "1000.00", 1, fixedContribution("5"), fixedChance("100"));
            when(jackpotRepository.findByIdForUpdate(JACKPOT_ID)).thenReturn(Optional.of(jackpot));
            when(betRepository.findById(BET_ID)).thenReturn(Optional.empty());
            DataIntegrityViolationException pkViolation = new DataIntegrityViolationException("pk_bet");
            when(betRepository.saveAndFlush(any(BetEntity.class))).thenThrow(pkViolation);

            assertThatThrownBy(() -> service.process(bet("200.00"))).isSameAs(pkViolation);

            verifyNoInteractions(contributionRepository, rewardRepository, evaluationRepository, rewardDraw,
                    eventPublisher);
        }
    }

    @Nested
    @DisplayName("isProcessed")
    class IsProcessed {

        @ParameterizedTest(name = "bet row exists = {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("reports whether a bet row exists")
        void reportsWhetherBetRowExists(boolean exists) {
            when(betRepository.existsById(BET_ID)).thenReturn(exists);

            assertThat(service.isProcessed(BET_ID)).isEqualTo(exists);
            verifyNoInteractions(jackpotRepository, contributionRepository, rewardRepository, evaluationRepository);
        }
    }

    @Test
    @DisplayName("transaction boundaries: process is READ_COMMITTED read-write, isProcessed is read-only")
    void transactionBoundaries() throws NoSuchMethodException {
        Transactional process = BetProcessingService.class.getMethod("process", Bet.class)
                .getAnnotation(Transactional.class);
        Transactional isProcessed = BetProcessingService.class.getMethod("isProcessed", String.class)
                .getAnnotation(Transactional.class);

        assertThat(process).isNotNull();
        assertThat(process.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(process.readOnly()).isFalse();
        assertThat(isProcessed).isNotNull();
        assertThat(isProcessed.readOnly()).isTrue();
    }
}
