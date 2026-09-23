package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.Money;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessingResult;
import com.sporty.jackpot.domain.model.ProcessingStatus;
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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one consumed bet in one database transaction, under the jackpot's row lock: idempotency check,
 * contribution, eager reward evaluation and (on a win) payout + pool reset.
 */
@Service
public class BetProcessingService {

    private static final Logger log = LoggerFactory.getLogger(BetProcessingService.class);

    private final JackpotRepository jackpotRepository;
    private final BetRepository betRepository;
    private final JackpotContributionRepository contributionRepository;
    private final JackpotRewardRepository rewardRepository;
    private final BetEvaluationRepository evaluationRepository;
    private final EntityMapper entityMapper;
    private final RewardDraw rewardDraw;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public BetProcessingService(JackpotRepository jackpotRepository, BetRepository betRepository,
                                JackpotContributionRepository contributionRepository,
                                JackpotRewardRepository rewardRepository,
                                BetEvaluationRepository evaluationRepository, EntityMapper entityMapper,
                                RewardDraw rewardDraw, ApplicationEventPublisher eventPublisher, Clock clock) {
        this.jackpotRepository = jackpotRepository;
        this.betRepository = betRepository;
        this.contributionRepository = contributionRepository;
        this.rewardRepository = rewardRepository;
        this.evaluationRepository = evaluationRepository;
        this.entityMapper = entityMapper;
        this.rewardDraw = rewardDraw;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Processes a bet exactly once.
     *
     * @param bet the consumed bet
     * @return the processing result; a bet id seen before is a no-op ({@code DUPLICATE})
     * @throws org.springframework.dao.DataIntegrityViolationException when a concurrent transaction stored the same
     *                                                                 bet id first (the caller resolves the race)
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcessingResult process(Bet bet) {
        Optional<JackpotEntity> jackpot = jackpotRepository.findByIdForUpdate(bet.jackpotId());
        Optional<BetEntity> existing = betRepository.findById(bet.betId());
        if (existing.isPresent()) {
            logDuplicate(bet, existing.get());
            return ProcessingResult.duplicate(bet.betId());
        }
        Instant now = clock.instant();
        if (jackpot.isEmpty()) {
            betRepository.saveAndFlush(new BetEntity(bet.betId(), bet.userId(), bet.jackpotId(), bet.amount(),
                    BetStatus.NO_MATCHING_JACKPOT, bet.placedAt(), now));
            eventPublisher.publishEvent(new BetProcessedEvent(ProcessingStatus.NO_MATCHING_JACKPOT, bet.betId(),
                    bet.jackpotId(), null, null, null, bet.placedAt(), now));
            return ProcessingResult.noMatchingJackpot(bet.betId());
        }
        return contributeAndEvaluate(bet, jackpot.get(), now);
    }

    /**
     * Whether a bet id has been processed (committed).
     *
     * @param betId bet id
     * @return {@code true} when a bet row exists
     */
    @Transactional(readOnly = true)
    public boolean isProcessed(String betId) {
        return betRepository.existsById(betId);
    }

    private ProcessingResult contributeAndEvaluate(Bet bet, JackpotEntity jackpot, Instant now) {
        BigDecimal initialPool = jackpot.getInitialPoolAmount();
        BigDecimal contributionAmount = jackpot.getContributionPolicy()
                .contributionAmount(bet.amount(), jackpot.getCurrentPoolAmount(), initialPool);
        BigDecimal poolAfter = jackpot.addContribution(contributionAmount, now);
        long cycle = jackpot.getCycle();

        betRepository.saveAndFlush(new BetEntity(bet.betId(), bet.userId(), bet.jackpotId(), bet.amount(),
                BetStatus.CONTRIBUTED, bet.placedAt(), now));
        JackpotContributionEntity contribution = contributionRepository.saveAndFlush(new JackpotContributionEntity(
                bet.betId(), bet.userId(), bet.jackpotId(), bet.amount(), contributionAmount, poolAfter, cycle, now));

        BigDecimal chance = Money.ZERO_PERCENT;
        EvaluationOutcome outcome = EvaluationOutcome.LOST;
        if (contributionAmount.signum() > 0) {
            chance = jackpot.getRewardPolicy().winChancePercentage(poolAfter, initialPool);
            outcome = rewardDraw.isWinning(chance) ? EvaluationOutcome.WON : EvaluationOutcome.LOST;
        }
        BigDecimal reward = Money.ZERO;
        if (outcome == EvaluationOutcome.WON) {
            reward = jackpot.award(now);
            rewardRepository.saveAndFlush(new JackpotRewardEntity(bet.betId(), bet.userId(), bet.jackpotId(), reward,
                    cycle, now));
        }
        BetEvaluationEntity evaluation = evaluationRepository.saveAndFlush(new BetEvaluationEntity(bet.betId(),
                bet.userId(), bet.jackpotId(), outcome, chance, reward, cycle, now));

        eventPublisher.publishEvent(new BetProcessedEvent(ProcessingStatus.PROCESSED, bet.betId(), bet.jackpotId(),
                outcome, contributionAmount, reward, bet.placedAt(), now));
        return ProcessingResult.processed(entityMapper.toContribution(contribution),
                entityMapper.toEvaluation(evaluation));
    }

    private static void logDuplicate(Bet bet, BetEntity stored) {
        boolean samePayload = stored.getUserId().equals(bet.userId())
                && stored.getJackpotId().equals(bet.jackpotId())
                && stored.getBetAmount().compareTo(bet.amount()) == 0;
        if (samePayload) {
            log.info("Duplicate bet {} ignored (already processed)", bet.betId());
        } else {
            log.warn("Duplicate bet {} ignored, but its payload differs from the processed bet: stored "
                            + "userId={} jackpotId={} amount={}, received userId={} jackpotId={} amount={}",
                    bet.betId(), stored.getUserId(), stored.getJackpotId(), stored.getBetAmount(), bet.userId(),
                    bet.jackpotId(), bet.amount());
        }
    }
}
