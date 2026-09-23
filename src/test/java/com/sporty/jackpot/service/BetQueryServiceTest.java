package com.sporty.jackpot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.exception.BetNotContributingException;
import com.sporty.jackpot.exception.BetNotFoundException;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotServiceException;
import com.sporty.jackpot.persistence.entity.BetEntity;
import com.sporty.jackpot.persistence.entity.BetEvaluationEntity;
import com.sporty.jackpot.persistence.entity.JackpotContributionEntity;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.persistence.repository.BetEvaluationRepository;
import com.sporty.jackpot.persistence.repository.BetRepository;
import com.sporty.jackpot.persistence.repository.JackpotContributionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@DisplayName("BetQueryService")
class BetQueryServiceTest {

    private static final String BET_ID = "bet-1";
    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:29.987654Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-09-23T10:15:30.123456Z");

    @Mock
    private BetRepository betRepository;
    @Mock
    private JackpotContributionRepository contributionRepository;
    @Mock
    private BetEvaluationRepository evaluationRepository;

    private BetQueryService service;

    @BeforeEach
    void setUp() {
        service = new BetQueryService(betRepository, contributionRepository, evaluationRepository,
                new EntityMapper());
    }

    private static BetEntity storedBet(BetStatus status) {
        return new BetEntity(BET_ID, "user-1", "jackpot-1", new BigDecimal("25.50"), status, PLACED_AT, PROCESSED_AT);
    }

    private static void assertMissing(JackpotServiceException exception,
                                      Class<? extends JackpotServiceException> expectedType, ErrorCode expectedCode) {
        assertThat(exception).isExactlyInstanceOf(expectedType);
        assertThat(exception.getErrorCode()).isEqualTo(expectedCode);
        assertThat(exception.getMessage()).contains("'" + BET_ID + "'");
    }

    /** The two lookups of a bet's processing result. */
    enum Lookup {
        CONTRIBUTION, EVALUATION;

        Object apply(BetQueryService service) {
            return this == CONTRIBUTION ? service.getContribution(BET_ID) : service.getEvaluation(BET_ID);
        }
    }

    private void givenNoResultRow(Lookup lookup) {
        if (lookup == Lookup.CONTRIBUTION) {
            when(contributionRepository.findByBetId(BET_ID)).thenReturn(Optional.empty());
        } else {
            when(evaluationRepository.findByBetId(BET_ID)).thenReturn(Optional.empty());
        }
    }

    @Test
    @DisplayName("is read-only transactional at class level")
    void isReadOnlyTransactional() {
        Transactional transactional = BetQueryService.class.getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    @DisplayName("getBet maps the stored bet")
    void getBetFound() {
        when(betRepository.findById(BET_ID)).thenReturn(Optional.of(new BetEntity(BET_ID, "user-1", "jackpot-1",
                new BigDecimal("25.50"), BetStatus.CONTRIBUTED, PLACED_AT, PROCESSED_AT)));

        ProcessedBet bet = service.getBet(BET_ID);

        assertThat(bet.betId()).isEqualTo(BET_ID);
        assertThat(bet.userId()).isEqualTo("user-1");
        assertThat(bet.jackpotId()).isEqualTo("jackpot-1");
        assertThat(bet.betAmount()).isEqualByComparingTo("25.50");
        assertThat(bet.status()).isEqualTo(BetStatus.CONTRIBUTED);
        assertThat(bet.placedAt()).isEqualTo(PLACED_AT);
        assertThat(bet.processedAt()).isEqualTo(PROCESSED_AT);
    }

    @Test
    @DisplayName("getBet of an unknown / not yet processed bet -> BET_NOT_FOUND")
    void getBetNotFound() {
        when(betRepository.findById(BET_ID)).thenReturn(Optional.empty());

        assertMissing(catchThrowableOfType(BetNotFoundException.class, () -> service.getBet(BET_ID)),
                BetNotFoundException.class, ErrorCode.BET_NOT_FOUND);
    }

    @Test
    @DisplayName("getContribution reads the bet row first, then maps the stored contribution")
    void getContributionFound() {
        when(betRepository.findById(BET_ID)).thenReturn(Optional.of(storedBet(BetStatus.CONTRIBUTED)));
        when(contributionRepository.findByBetId(BET_ID)).thenReturn(Optional.of(new JackpotContributionEntity(BET_ID,
                "user-1", "jackpot-1", new BigDecimal("250.00"), new BigDecimal("12.50"), new BigDecimal("1012.50"),
                3, PROCESSED_AT)));

        Contribution contribution = service.getContribution(BET_ID);

        assertThat(contribution).isEqualTo(new Contribution(BET_ID, "user-1", "jackpot-1", new BigDecimal("250.00"),
                new BigDecimal("12.50"), new BigDecimal("1012.50"), 3, PROCESSED_AT));
        InOrder order = inOrder(betRepository, contributionRepository);
        order.verify(betRepository).findById(BET_ID);
        order.verify(contributionRepository).findByBetId(BET_ID);
    }

    @Test
    @DisplayName("getEvaluation reads the bet row first, then maps the stored evaluation")
    void getEvaluationFound() {
        when(betRepository.findById(BET_ID)).thenReturn(Optional.of(storedBet(BetStatus.CONTRIBUTED)));
        when(evaluationRepository.findByBetId(BET_ID)).thenReturn(Optional.of(new BetEvaluationEntity(BET_ID, "user-1",
                "jackpot-1", EvaluationOutcome.WON, new BigDecimal("1.0000"), new BigDecimal("1010.00"), 3,
                PROCESSED_AT)));

        BetEvaluation evaluation = service.getEvaluation(BET_ID);

        assertThat(evaluation).isEqualTo(new BetEvaluation(BET_ID, "user-1", "jackpot-1", EvaluationOutcome.WON,
                new BigDecimal("1.0000"), new BigDecimal("1010.00"), 3, PROCESSED_AT));
        assertThat(evaluation.won()).isTrue();
        InOrder order = inOrder(betRepository, evaluationRepository);
        order.verify(betRepository).findById(BET_ID);
        order.verify(evaluationRepository).findByBetId(BET_ID);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Lookup.class)
    @DisplayName("no bet row (unknown or not processed yet) -> BET_NOT_FOUND, the result tables are not read")
    void unknownBetIsNotFound(Lookup lookup) {
        when(betRepository.findById(BET_ID)).thenReturn(Optional.empty());

        assertMissing(catchThrowableOfType(JackpotServiceException.class, () -> lookup.apply(service)),
                BetNotFoundException.class, ErrorCode.BET_NOT_FOUND);
        verifyNoInteractions(contributionRepository, evaluationRepository);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Lookup.class)
    @DisplayName("a bet stored as NO_MATCHING_JACKPOT -> BET_NOT_CONTRIBUTING, the result tables are not read")
    void betWithoutJackpotIsNotContributing(Lookup lookup) {
        when(betRepository.findById(BET_ID)).thenReturn(Optional.of(storedBet(BetStatus.NO_MATCHING_JACKPOT)));

        assertMissing(catchThrowableOfType(JackpotServiceException.class, () -> lookup.apply(service)),
                BetNotContributingException.class, ErrorCode.BET_NOT_CONTRIBUTING);
        verifyNoInteractions(contributionRepository, evaluationRepository);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Lookup.class)
    @DisplayName("a CONTRIBUTED bet without its result row is a broken invariant (never 404 or 422)")
    void contributedBetWithoutResultRowIsABrokenInvariant(Lookup lookup) {
        when(betRepository.findById(BET_ID)).thenReturn(Optional.of(storedBet(BetStatus.CONTRIBUTED)));
        givenNoResultRow(lookup);

        assertThatThrownBy(() -> lookup.apply(service))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Bet '" + BET_ID + "' is CONTRIBUTED but has no stored " + lookup.name().toLowerCase(Locale.ROOT));
    }
}
