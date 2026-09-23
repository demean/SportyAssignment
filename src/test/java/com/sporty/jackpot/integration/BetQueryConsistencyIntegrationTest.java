package com.sporty.jackpot.integration;

import static com.sporty.jackpot.support.TestJackpots.fixedChance;
import static com.sporty.jackpot.support.TestJackpots.fixedContribution;
import static com.sporty.jackpot.support.TestJackpots.uniqueId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;

import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessingResult;
import com.sporty.jackpot.exception.BetNotFoundException;
import com.sporty.jackpot.persistence.mapper.EntityMapper;
import com.sporty.jackpot.persistence.repository.BetEvaluationRepository;
import com.sporty.jackpot.persistence.repository.BetRepository;
import com.sporty.jackpot.persistence.repository.JackpotContributionRepository;
import com.sporty.jackpot.service.BetQueryService;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The result lookups are polled while the consumer commits (T9). A lookup that starts just before the commit must
 * answer "not processed yet" ({@code 404}, keep polling) and the next poll the result; it must never answer
 * {@code 422 BET_NOT_CONTRIBUTING}, which tells the client, for good, that the bet took no part.
 *
 * <p>The interleaving is forced, not hoped for: the lookup runs in a read-only transaction like the real service, on
 * repositories that let the consumer commit the bet right after the lookup's FIRST statement, against the real H2
 * database (READ_COMMITTED, one snapshot per statement).
 */
@DisplayName("Bet lookups racing the consumer's commit (T9)")
class BetQueryConsistencyIntegrationTest extends AbstractProcessingServiceIntegrationTest {

    @Autowired
    private BetRepository betRepository;

    @Autowired
    private JackpotContributionRepository contributionRepository;

    @Autowired
    private BetEvaluationRepository evaluationRepository;

    @Autowired
    private EntityMapper entityMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    static Stream<Arguments> lookups() {
        return Stream.of(
                arguments(named("contribution",
                        (BiFunction<BetQueryService, String, Object>) BetQueryService::getContribution)),
                arguments(named("evaluation",
                        (BiFunction<BetQueryService, String, Object>) BetQueryService::getEvaluation)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lookups")
    @DisplayName("a commit between the lookup's statements answers 404 (keep polling), then the result; never 422")
    void commitBetweenTheStatementsIsNeverReportedAsNotContributing(
            BiFunction<BetQueryService, String, Object> lookup) {
        String jackpotId = jackpots.create("100.00", fixedContribution("10.0"), fixedChance("100"));
        Bet bet = new Bet(uniqueId("bet"), "user-race", jackpotId, new BigDecimal("50.00"), clock.instant());
        AtomicReference<ProcessingResult> processed = new AtomicReference<>();
        Runnable commitTheBetOnce = () -> {
            if (processed.get() == null) {
                // another thread: processing must not join the lookup's read-only transaction
                processed.set(CompletableFuture.supplyAsync(() -> processingService.process(bet)).join());
            }
        };
        BetQueryService racingLookups = new BetQueryService(
                runAfterEveryCall(BetRepository.class, betRepository, commitTheBetOnce),
                runAfterEveryCall(JackpotContributionRepository.class, contributionRepository, commitTheBetOnce),
                runAfterEveryCall(BetEvaluationRepository.class, evaluationRepository, commitTheBetOnce),
                entityMapper);
        TransactionTemplate readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        TransactionCallback<Object> poll = status -> lookup.apply(racingLookups, bet.betId());

        Throwable firstPoll = catchThrowable(() -> readOnly.execute(poll));
        Object secondPoll = readOnly.execute(poll);

        assertThat(processed.get()).as("committed right after the first statement of the first poll").isNotNull();
        assertThat(processed.get().evaluation().outcome()).isEqualTo(EvaluationOutcome.WON);
        assertThat(firstPoll).as("not processed when the lookup started: 404, keep polling")
                .isExactlyInstanceOf(BetNotFoundException.class);
        assertThat(secondPoll).isIn(processed.get().contribution(), processed.get().evaluation());
        assertThat(secondPoll).isInstanceOfAny(Contribution.class, BetEvaluation.class);
    }

    /** A repository that calls the real one, then runs {@code afterCall} (the forced interleaving point). */
    private static <T> T runAfterEveryCall(Class<T> type, T repository, Runnable afterCall) {
        return mock(type, invocation -> {
            Object result = invocation.getMethod().invoke(repository, invocation.getArguments());
            afterCall.run();
            return result;
        });
    }
}
