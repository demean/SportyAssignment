package com.sporty.jackpot.service;

import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.exception.BetNotContributingException;
import com.sporty.jackpot.exception.BetNotFoundException;
import com.sporty.jackpot.exception.JackpotServiceException;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.persistence.repository.BetEvaluationRepository;
import com.sporty.jackpot.persistence.repository.BetRepository;
import com.sporty.jackpot.persistence.repository.JackpotContributionRepository;
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
        return contributionRepository.findByBetId(betId)
                .map(entityMapper::toContribution)
                .orElseThrow(() -> missing(betId));
    }

    /**
     * @param betId bet id
     * @return the bet's evaluation (did it win, and the reward)
     * @throws BetNotFoundException        when the bet is unknown or not processed yet
     * @throws BetNotContributingException when the bet was processed without a matching jackpot
     */
    public BetEvaluation getEvaluation(String betId) {
        return evaluationRepository.findByBetId(betId)
                .map(entityMapper::toEvaluation)
                .orElseThrow(() -> missing(betId));
    }

    private JackpotServiceException missing(String betId) {
        return betRepository.existsById(betId) ? new BetNotContributingException(betId) : new BetNotFoundException(betId);
    }
}
