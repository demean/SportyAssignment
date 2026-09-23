package com.sporty.jackpot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

    /** Bet row exists -> 422 BET_NOT_CONTRIBUTING (no matching jackpot); no bet row -> 404 BET_NOT_FOUND. */
    static Stream<Arguments> missingRow() {
        return Stream.of(
                Arguments.of(true, BetNotContributingException.class, ErrorCode.BET_NOT_CONTRIBUTING),
                Arguments.of(false, BetNotFoundException.class, ErrorCode.BET_NOT_FOUND));
    }

    private static void assertMissing(JackpotServiceException exception,
                                      Class<? extends JackpotServiceException> expectedType, ErrorCode expectedCode) {
        assertThat(exception).isExactlyInstanceOf(expectedType);
        assertThat(exception.getErrorCode()).isEqualTo(expectedCode);
        assertThat(exception.getMessage()).contains("'" + BET_ID + "'");
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
    @DisplayName("getContribution maps the stored contribution without checking the bet row")
    void getContributionFound() {
        when(contributionRepository.findByBetId(BET_ID)).thenReturn(Optional.of(new JackpotContributionEntity(BET_ID,
                "user-1", "jackpot-1", new BigDecimal("250.00"), new BigDecimal("12.50"), new BigDecimal("1012.50"),
                3, PROCESSED_AT)));

        Contribution contribution = service.getContribution(BET_ID);

        assertThat(contribution).isEqualTo(new Contribution(BET_ID, "user-1", "jackpot-1", new BigDecimal("250.00"),
                new BigDecimal("12.50"), new BigDecimal("1012.50"), 3, PROCESSED_AT));
        verify(betRepository, never()).existsById(BET_ID);
    }

    @ParameterizedTest(name = "bet row exists = {0} -> {2}")
    @MethodSource("missingRow")
    @DisplayName("getContribution without a row -> BET_NOT_CONTRIBUTING if the bet exists, else BET_NOT_FOUND")
    void getContributionMissing(boolean betExists, Class<? extends JackpotServiceException> expectedType,
                                ErrorCode expectedCode) {
        when(contributionRepository.findByBetId(BET_ID)).thenReturn(Optional.empty());
        when(betRepository.existsById(BET_ID)).thenReturn(betExists);

        assertMissing(catchThrowableOfType(JackpotServiceException.class, () -> service.getContribution(BET_ID)),
                expectedType, expectedCode);
    }

    @Test
    @DisplayName("getEvaluation maps the stored evaluation without checking the bet row")
    void getEvaluationFound() {
        when(evaluationRepository.findByBetId(BET_ID)).thenReturn(Optional.of(new BetEvaluationEntity(BET_ID, "user-1",
                "jackpot-1", EvaluationOutcome.WON, new BigDecimal("1.0000"), new BigDecimal("1010.00"), 3,
                PROCESSED_AT)));

        BetEvaluation evaluation = service.getEvaluation(BET_ID);

        assertThat(evaluation).isEqualTo(new BetEvaluation(BET_ID, "user-1", "jackpot-1", EvaluationOutcome.WON,
                new BigDecimal("1.0000"), new BigDecimal("1010.00"), 3, PROCESSED_AT));
        assertThat(evaluation.won()).isTrue();
        verify(betRepository, never()).existsById(BET_ID);
    }

    @ParameterizedTest(name = "bet row exists = {0} -> {2}")
    @MethodSource("missingRow")
    @DisplayName("getEvaluation without a row -> BET_NOT_CONTRIBUTING if the bet exists, else BET_NOT_FOUND")
    void getEvaluationMissing(boolean betExists, Class<? extends JackpotServiceException> expectedType,
                              ErrorCode expectedCode) {
        when(evaluationRepository.findByBetId(BET_ID)).thenReturn(Optional.empty());
        when(betRepository.existsById(BET_ID)).thenReturn(betExists);

        assertMissing(catchThrowableOfType(JackpotServiceException.class, () -> service.getEvaluation(BET_ID)),
                expectedType, expectedCode);
    }
}
