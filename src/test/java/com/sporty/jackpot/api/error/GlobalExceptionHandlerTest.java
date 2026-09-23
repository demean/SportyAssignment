package com.sporty.jackpot.api.error;

import static com.sporty.jackpot.api.ProblemDetailAssertions.assertNoRetryAfter;
import static com.sporty.jackpot.api.ProblemDetailAssertions.assertProblem;
import static com.sporty.jackpot.api.ProblemDetailAssertions.assertRetryAfterOneSecond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.BDDMockito.given;

import com.sporty.jackpot.api.ApiWebMvcTestConfiguration;
import com.sporty.jackpot.api.controller.BetController;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import com.sporty.jackpot.service.BetPublishingService;
import com.sporty.jackpot.service.BetQueryService;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.server.ResponseStatusException;

/**
 * The cross-cutting mappings of {@link GlobalExceptionHandler}, driven through a real MVC stack: the bet lookup
 * endpoint throws whatever the (mocked) query service throws. Controller-specific codes are covered by the
 * controller tests.
 */
@WebMvcTest(BetController.class)
@Import(ApiWebMvcTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private static final String BET_ID = "bet-1";
    private static final String BET_URI = "/api/v1/bets/" + BET_ID;
    /** Internal detail that must never reach a client. */
    private static final String SECRET = "jdbc:postgresql://db-internal:5432/jackpot password=hunter2";

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private BetPublishingService publishingService;

    @MockitoBean
    private BetQueryService queryService;

    private MvcTestResult getBetFailingWith(RuntimeException failure) {
        given(queryService.getBet(BET_ID)).willThrow(failure);
        return mvc.get().uri(BET_URI).exchange();
    }

    @Nested
    @DisplayName("transient data access failures")
    class TransientFailures {

        static Stream<Arguments> transientFailures() {
            return Stream.of(
                    arguments(named("lock timeout (CannotAcquireLockException)",
                            new CannotAcquireLockException(SECRET))),
                    arguments(named("no connection (CannotCreateTransactionException)",
                            new CannotCreateTransactionException(SECRET))),
                    arguments(named("database down (DataAccessResourceFailureException)",
                            new DataAccessResourceFailureException(SECRET))),
                    arguments(named("query timeout (TransientDataAccessException)",
                            new QueryTimeoutException(SECRET))),
                    arguments(named("optimistic lock (ConcurrencyFailureException)",
                            new OptimisticLockingFailureException(SECRET))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("transientFailures")
        @DisplayName("are answered with 503 TEMPORARILY_UNAVAILABLE, Retry-After and a generic detail")
        void mapsToTemporarilyUnavailable(RuntimeException failure) {
            MvcTestResult result = getBetFailingWith(failure);

            assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.TEMPORARILY_UNAVAILABLE,
                    GlobalExceptionHandler.TEMPORARILY_UNAVAILABLE_DETAIL);
            assertRetryAfterOneSecond(result);
            assertThat(result).bodyText().doesNotContain("hunter2");
        }
    }

    @Nested
    @DisplayName("data integrity violations")
    class DataIntegrityViolations {

        @ParameterizedTest(name = "{0}")
        @ValueSource(classes = {DataIntegrityViolationException.class, DuplicateKeyException.class})
        @DisplayName("are answered with 409 CONFLICT and a generic detail")
        void mapsToConflict(Class<? extends DataIntegrityViolationException> type) throws Exception {
            DataIntegrityViolationException failure = type.getConstructor(String.class, Throwable.class)
                    .newInstance(SECRET, new SQLException("duplicate key value violates unique constraint pk_bet"));

            MvcTestResult result = getBetFailingWith(failure);

            assertProblem(result, HttpStatus.CONFLICT, ErrorCode.CONFLICT, GlobalExceptionHandler.CONFLICT_DETAIL);
            assertNoRetryAfter(result);
            assertThat(result).bodyText().doesNotContain("hunter2").doesNotContain("pk_bet");
        }
    }

    @Nested
    @DisplayName("unexpected failures")
    class UnexpectedFailures {

        static Stream<Arguments> unexpectedFailures() {
            return Stream.of(
                    arguments(named("RuntimeException", new RuntimeException(SECRET))),
                    arguments(named("IllegalStateException", new IllegalStateException(SECRET))),
                    arguments(named("NullPointerException", new NullPointerException(SECRET))),
                    arguments(named("JackpotConfigurationException",
                            new JackpotConfigurationException("Invalid reward policy JSON: " + SECRET))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("unexpectedFailures")
        @DisplayName("are answered with 500 INTERNAL_ERROR and a generic detail, and logged at ERROR")
        void mapsToInternalError(RuntimeException failure, CapturedOutput output) {
            MvcTestResult result = getBetFailingWith(failure);

            assertProblem(result, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                    GlobalExceptionHandler.INTERNAL_ERROR_DETAIL);
            assertNoRetryAfter(result);
            assertThat(result).bodyText().doesNotContain("hunter2").doesNotContain(failure.getClass().getName());
            assertThat(output).contains("ERROR", "Unexpected error while handling a request",
                    failure.getClass().getName() + ": " + failure.getMessage());
        }
    }

    @Nested
    @DisplayName("exceptions resolved by the Spring MVC base handler")
    class BaseHandlerExceptions {

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {"/api/v1/unknown", "/api/v1/bets/bet-1/unknown", "/api/v2/bets/bet-1"})
        @DisplayName("an unknown path is answered with 404 RESOURCE_NOT_FOUND")
        void mapsUnknownPathToResourceNotFound(String path) {
            MvcTestResult result = mvc.get().uri(path).exchange();

            assertProblem(result, HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND);
            assertNoRetryAfter(result);
        }

        static Stream<Arguments> errorResponses() {
            return Stream.of(
                    arguments(named("ResponseStatusException 404", new ResponseStatusException(HttpStatus.NOT_FOUND)),
                            HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND),
                    arguments(named("ResponseStatusException 410", new ResponseStatusException(HttpStatus.GONE)),
                            HttpStatus.GONE, ErrorCode.MALFORMED_REQUEST),
                    arguments(named("ErrorResponseException 429",
                                    new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS)),
                            HttpStatus.TOO_MANY_REQUESTS, ErrorCode.MALFORMED_REQUEST),
                    arguments(named("ErrorResponseException 502", new ErrorResponseException(HttpStatus.BAD_GATEWAY)),
                            HttpStatus.BAD_GATEWAY, ErrorCode.INTERNAL_ERROR),
                    arguments(named("AsyncRequestTimeoutException (503)", new AsyncRequestTimeoutException()),
                            HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.INTERNAL_ERROR));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("errorResponses")
        @DisplayName("get a code derived from the status: 404/405/406/415 own codes, other 4xx MALFORMED_REQUEST,"
                + " 5xx INTERNAL_ERROR")
        void derivesCodeFromStatus(RuntimeException failure, HttpStatus status, ErrorCode code) {
            MvcTestResult result = getBetFailingWith(failure);

            assertProblem(result, status, code);
        }
    }
}
