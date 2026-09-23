package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.exception.BetNotContributingException;
import com.sporty.jackpot.exception.BetNotFoundException;
import com.sporty.jackpot.persistence.entity.BetEntity;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.persistence.repository.BetEvaluationRepository;
import com.sporty.jackpot.persistence.repository.BetRepository;
import com.sporty.jackpot.persistence.repository.JackpotContributionRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only lookups of processed bets, their contributions and their (stored) evaluations. Never draws or writes.
 */
@Service
@Transactional(readOnly = true)
public class BetQueryService {

    private final BetRepository betRepository;
    private final JackpotContributionRepository contributionRepository;
    private final BetEvaluationRepository evaluationRepository;
    private final EntityMapper entityMapper;

    public BetQueryService(BetRepository betRepository, JackpotContributionRepository contributionRepository,
                           BetEvaluationRepository evaluationRepository, EntityMapper entityMapper) {
        this.betRepository = betRepository;
        this.contributionRepository = contributionRepository;
        this.evaluationRepository = evaluationRepository;
        this.entityMapper = entityMapper;
    }

    /**
     * @param betId bet id
     * @return the processed bet
     * @throws BetNotFoundException when the bet is unknown or not processed yet
     */
    public ProcessedBet getBet(String betId) {
        return betRepository.findById(betId)
                .map(entityMapper::toProcessedBet)
                .orElseThrow(() -> new BetNotFoundException(betId));
    }

    /**
     * @param betId bet id
     * @return the bet's contribution
     * @throws BetNotFoundException        when the bet is unknown or not processed yet
     * @throws BetNotContributingException when the bet was processed without a matching jackpot
     */
    public Contribution getContribution(String betId) {
        requireContributingBet(betId);
        return entityMapper.toContribution(stored(contributionRepository.findByBetId(betId), "contribution", betId));
    }

    /**
     * @param betId bet id
     * @return the bet's evaluation (did it win, and the reward)
     * @throws BetNotFoundException        when the bet is unknown or not processed yet
     * @throws BetNotContributingException when the bet was processed without a matching jackpot
     */
    public BetEvaluation getEvaluation(String betId) {
        requireContributingBet(betId);
        return entityMapper.toEvaluation(stored(evaluationRepository.findByBetId(betId), "evaluation", betId));
    }

    /**
     * Decides 404 / 422 from the bet row itself, read FIRST. Each statement of a READ_COMMITTED transaction sees its
     * own snapshot, so inferring "processed without a jackpot" from a miss on the child table followed by a hit on
     * the bet row answered 422 for a contributing bet whose processing committed between the two statements.
     */
    private void requireContributingBet(String betId) {
        BetEntity bet = betRepository.findById(betId).orElseThrow(() -> new BetNotFoundException(betId));
        if (bet.getStatus() == BetStatus.NO_MATCHING_JACKPOT) {
            throw new BetNotContributingException(betId);
        }
    }

    /**
     * A CONTRIBUTED bet's contribution and evaluation rows are committed in the same transaction as the bet row, so
     * a later statement always sees them: a missing row is a broken invariant, never "not processed yet".
     */
    private static <T> T stored(Optional<T> row, String kind, String betId) {
        return row.orElseThrow(() -> new IllegalStateException("Bet '" + betId + "' is " + BetStatus.CONTRIBUTED
                + " but has no stored " + kind));
    }
}
