package com.sporty.jackpot.api.controller;

import static com.sporty.jackpot.api.ApiWebMvcTestConfiguration.NOW;
import static com.sporty.jackpot.api.ProblemDetailAssertions.assertNoRetryAfter;
import static com.sporty.jackpot.api.ProblemDetailAssertions.assertProblem;
import static com.sporty.jackpot.api.ProblemDetailAssertions.assertRetryAfterOneSecond;
import static com.sporty.jackpot.api.ProblemDetailAssertions.assertValidationFailed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.sporty.jackpot.api.ApiWebMvcTestConfiguration;
import com.sporty.jackpot.api.error.FieldErrorDto;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.BetEvaluation;
import com.sporty.jackpot.domain.model.BetStatus;
import com.sporty.jackpot.domain.model.Contribution;
import com.sporty.jackpot.domain.model.EvaluationOutcome;
import com.sporty.jackpot.domain.model.ProcessedBet;
import com.sporty.jackpot.exception.BetNotContributingException;
import com.sporty.jackpot.exception.BetNotFoundException;
import com.sporty.jackpot.exception.BetPublishingException;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.InvalidBetException;
import com.sporty.jackpot.service.BetPlacementService;
import com.sporty.jackpot.service.BetQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@WebMvcTest(BetController.class)
@Import(ApiWebMvcTestConfiguration.class)
@DisplayName("BetController (web slice)")
class BetControllerTest {

    private static final String BETS = "/api/v1/bets";
    private static final String ID_PATTERN_MESSAGE = "must match \"" + Bet.ID_REGEX + "\"";
    private static final String NOT_BLANK_MESSAGE = "must not be blank";
    private static final String DIGITS_MESSAGE = "numeric value out of bounds (<10 digits>.<2 digits> expected)";
    private static final String MIN_AMOUNT_MESSAGE = "must be greater than or equal to 0.01";
    private static final String MAX_AMOUNT_MESSAGE = "must be less than or equal to 1000000000.00";
    private static final Instant PLACED_AT = Instant.parse("2026-09-23T10:15:30.100001Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-09-23T10:15:30.200002Z");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private BetPlacementService placementService;

    @MockitoBean
    private BetQueryService queryService;

    /** The placement service accepts every bet: it returns it accepted at {@link ApiWebMvcTestConfiguration#NOW}. */
    private void givenPlacementAcceptsBets() {
        given(placementService.place(any(), any(), any(), any())).willAnswer(invocation -> new Bet(
                invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                invocation.getArgument(3), NOW));
    }

    private void givenPlacementFailsWith(RuntimeException failure) {
        given(placementService.place(any(), any(), any(), any())).willThrow(failure);
    }

    private MvcTestResult postBet(String json) {
        return mvc.post().uri(BETS).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }

    /** JSON of a {@code PlaceBetRequest}; {@code null} ids are sent as JSON null, the amount is a raw JSON literal. */
    private static String requestJson(String betId, String userId, String jackpotId, String amountLiteral) {
        return "{\"betId\":%s,\"userId\":%s,\"jackpotId\":%s,\"betAmount\":%s}"
                .formatted(quoted(betId), quoted(userId), quoted(jackpotId), amountLiteral);
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static FieldErrorDto error(String field, String message) {
        return new FieldErrorDto(field, message);
    }

    @Nested
    @DisplayName("POST /api/v1/bets")
    class PlaceBet {

        @Test
        @DisplayName("hands the request to the placement service and answers 202 with Location and body")
        void acceptsValidBet() {
            givenPlacementAcceptsBets();

            MvcTestResult result = postBet(requestJson("bet-1001", "user-42", "jackpot-lucky", "250"));

            assertThat(result).hasStatus(HttpStatus.ACCEPTED)
                    .hasContentType(MediaType.APPLICATION_JSON)
                    .hasHeader(HttpHeaders.LOCATION, "/api/v1/bets/bet-1001");
            assertThat(result).bodyJson().isStrictlyEqualTo("""
                    {"betId":"bet-1001","jackpotId":"jackpot-lucky","status":"ACCEPTED",
                     "acceptedAt":"2026-09-23T10:15:30.123456Z"}
                    """);
            ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
            then(placementService).should().place(eq("bet-1001"), eq("user-42"), eq("jackpot-lucky"),
                    amount.capture());
            then(placementService).shouldHaveNoMoreInteractions();
            assertThat(amount.getValue()).isEqualByComparingTo("250");
        }

        @Test
        @DisplayName("reports the placed bet's placedAt (the time it was accepted) as acceptedAt")
        void acceptedAtIsThePlacedBetsInstant() {
            Instant placedAt = Instant.parse("2026-09-23T10:15:31Z");
            given(placementService.place(any(), any(), any(), any())).willReturn(
                    new Bet("bet-1002", "user-42", "jackpot-fixed", new BigDecimal("10.50"), placedAt));

            MvcTestResult result = postBet(requestJson("bet-1002", "user-42", "jackpot-fixed", "10.50"));

            assertThat(result).hasStatus(HttpStatus.ACCEPTED);
            assertThat(result).bodyJson().extractingPath("$.acceptedAt").isEqualTo(placedAt.toString());
        }

        @ParameterizedTest(name = "betId={0}, betAmount={1}")
        @CsvSource({
                "a, 0.01",
                "AZaz09._:-x, 1000000000.00",
                "bet.1:a_b-C, 1000000000",
                "0123456789012345678901234567890123456789012345678901234567890123, 1.5",
                "b, 7.10",
                "..., 1.00",
                ".a., 2.00"
        })
        @DisplayName("accepts every id character and length allowed by Bet.ID_REGEX and the amount bounds")
        void acceptsBoundaryValues(String betId, String amount) {
            givenPlacementAcceptsBets();

            MvcTestResult result = postBet(requestJson(betId, "user:1", "jackpot_1", amount));

            assertThat(result).hasStatus(HttpStatus.ACCEPTED).hasHeader(HttpHeaders.LOCATION, BETS + "/" + betId);
            ArgumentCaptor<BigDecimal> placedAmount = ArgumentCaptor.forClass(BigDecimal.class);
            then(placementService).should().place(eq(betId), eq("user:1"), eq("jackpot_1"), placedAmount.capture());
            assertThat(placedAmount.getValue()).isEqualByComparingTo(amount);
        }

        static Stream<Arguments> invalidRequests() {
            String tooLong = "x".repeat(65);
            return Stream.of(
                    arguments(named("blank betId", requestJson("", "user-42", "jackpot-lucky", "250.00")),
                            List.of(error("betId", ID_PATTERN_MESSAGE), error("betId", NOT_BLANK_MESSAGE))),
                    arguments(named("whitespace betId", requestJson("   ", "user-42", "jackpot-lucky", "250.00")),
                            List.of(error("betId", ID_PATTERN_MESSAGE), error("betId", NOT_BLANK_MESSAGE))),
                    arguments(named("missing betId", requestJson(null, "user-42", "jackpot-lucky", "250.00")),
                            List.of(error("betId", NOT_BLANK_MESSAGE))),
                    arguments(named("betId with illegal characters",
                                    requestJson("bet 1!", "user-42", "jackpot-lucky", "250.00")),
                            List.of(error("betId", ID_PATTERN_MESSAGE))),
                    arguments(named("betId longer than 64", requestJson(tooLong, "user-42", "jackpot-lucky", "250.00")),
                            List.of(error("betId", ID_PATTERN_MESSAGE))),
                    arguments(named("betId '.' (URL dot segment)", requestJson(".", "user-42", "jackpot-lucky", "1")),
                            List.of(error("betId", ID_PATTERN_MESSAGE))),
                    arguments(named("betId '..' (URL dot segment)", requestJson("..", "user-42", "jackpot-lucky", "1")),
                            List.of(error("betId", ID_PATTERN_MESSAGE))),
                    arguments(named("jackpotId '..' (URL dot segment)", requestJson("bet-1", "user-42", "..", "1")),
                            List.of(error("jackpotId", ID_PATTERN_MESSAGE))),
                    arguments(named("blank userId", requestJson("bet-1", "", "jackpot-lucky", "250.00")),
                            List.of(error("userId", ID_PATTERN_MESSAGE), error("userId", NOT_BLANK_MESSAGE))),
                    arguments(named("userId longer than 64", requestJson("bet-1", tooLong, "jackpot-lucky", "250.00")),
                            List.of(error("userId", ID_PATTERN_MESSAGE))),
                    arguments(named("missing jackpotId", requestJson("bet-1", "user-42", null, "250.00")),
                            List.of(error("jackpotId", NOT_BLANK_MESSAGE))),
                    arguments(named("jackpotId with a slash", requestJson("bet-1", "user-42", "jackpot/1", "250.00")),
                            List.of(error("jackpotId", ID_PATTERN_MESSAGE))),
                    arguments(named("missing betAmount", requestJson("bet-1", "user-42", "jackpot-lucky", "null")),
                            List.of(error("betAmount", "must not be null"))),
                    arguments(named("betAmount 0.00", requestJson("bet-1", "user-42", "jackpot-lucky", "0.00")),
                            List.of(error("betAmount", MIN_AMOUNT_MESSAGE))),
                    arguments(named("negative betAmount", requestJson("bet-1", "user-42", "jackpot-lucky", "-1.00")),
                            List.of(error("betAmount", MIN_AMOUNT_MESSAGE))),
                    arguments(named("betAmount with 3 decimals",
                                    requestJson("bet-1", "user-42", "jackpot-lucky", "1.005")),
                            List.of(error("betAmount", DIGITS_MESSAGE))),
                    arguments(named("betAmount above the maximum",
                                    requestJson("bet-1", "user-42", "jackpot-lucky", "1000000000.01")),
                            List.of(error("betAmount", MAX_AMOUNT_MESSAGE))),
                    arguments(named("betAmount with 11 integer digits",
                                    requestJson("bet-1", "user-42", "jackpot-lucky", "10000000000")),
                            List.of(error("betAmount", MAX_AMOUNT_MESSAGE), error("betAmount", DIGITS_MESSAGE))),
                    arguments(named("every field invalid", requestJson("", null, "x y", "0.001")),
                            List.of(error("betAmount", MIN_AMOUNT_MESSAGE),
                                    error("betAmount", DIGITS_MESSAGE),
                                    error("betId", ID_PATTERN_MESSAGE),
                                    error("betId", NOT_BLANK_MESSAGE),
                                    error("jackpotId", ID_PATTERN_MESSAGE),
                                    error("userId", NOT_BLANK_MESSAGE))));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidRequests")
        @DisplayName("rejects an invalid request with 400 VALIDATION_FAILED, errors sorted by field then message")
        void rejectsInvalidRequest(String json, List<FieldErrorDto> expectedErrors) {
            MvcTestResult result = postBet(json);

            assertValidationFailed(result, expectedErrors);
            assertNoRetryAfter(result);
            then(placementService).shouldHaveNoInteractions();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "{oops",
                "",
                "[\"bet-1\"]",
                "{\"betId\":\"bet-1\",\"userId\":\"user-42\",\"jackpotId\":\"jackpot-lucky\",\"betAmount\":\"lots\"}",
                "{\"betId\":\"bet-1\",\"userId\":\"user-42\",\"jackpotId\":\"jackpot-lucky\",\"betAmount\":250.00",
                // strict parsing: a duplicate key is never last-wins (a gateway may have read the first stake) ...
                "{\"betId\":\"bet-1\",\"userId\":\"user-42\",\"jackpotId\":\"jackpot-lucky\",\"betAmount\":1,"
                        + "\"betAmount\":999999999}",
                // ... and the stake must be a JSON number, as documented, not a string coerced into one
                "{\"betId\":\"bet-1\",\"userId\":\"user-42\",\"jackpotId\":\"jackpot-lucky\",\"betAmount\":\"12.50\"}"
        })
        @DisplayName("answers an unreadable body with 400 MALFORMED_REQUEST")
        void rejectsMalformedBody(String body) {
            MvcTestResult result = postBet(body);

            assertProblem(result, HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST, "Failed to read request");
            assertThat(result).bodyJson().doesNotHavePath("$.errors");
            then(placementService).shouldHaveNoInteractions();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE,
                MediaType.APPLICATION_FORM_URLENCODED_VALUE})
        @DisplayName("answers a non-JSON body with 415 UNSUPPORTED_MEDIA_TYPE and advertises application/json")
        void rejectsUnsupportedContentType(String contentType) {
            MvcTestResult result = mvc.post().uri(BETS).contentType(contentType)
                    .content(requestJson("bet-1", "user-42", "jackpot-lucky", "250.00")).exchange();

            assertProblem(result, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE);
            assertThat(result).hasHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            then(placementService).shouldHaveNoInteractions();
        }

        @ParameterizedTest(name = "Accept: {0}")
        @ValueSource(strings = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_PLAIN_VALUE, "text/csv"})
        @DisplayName("answers a client that cannot accept JSON with 406 NOT_ACCEPTABLE without publishing the bet")
        void rejectsUnacceptableResponseTypeBeforePublishing(String accept) {
            MvcTestResult result = mvc.post().uri(BETS).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, accept)
                    .content(requestJson("bet-1001", "user-42", "jackpot-lucky", "250.00")).exchange();

            assertProblem(result, HttpStatus.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE);
            assertNoRetryAfter(result);
            then(placementService).shouldHaveNoInteractions();
        }

        @ParameterizedTest(name = "Accept: {0}")
        @ValueSource(strings = {MediaType.APPLICATION_JSON_VALUE, "application/*+json", "application/*", "*/*"})
        @DisplayName("accepts a bet from a client accepting JSON")
        void acceptsJsonCompatibleAcceptHeader(String accept) {
            givenPlacementAcceptsBets();

            MvcTestResult result = mvc.post().uri(BETS).contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.ACCEPT, accept)
                    .content(requestJson("bet-1001", "user-42", "jackpot-lucky", "250.00")).exchange();

            assertThat(result).hasStatus(HttpStatus.ACCEPTED).hasContentType(MediaType.APPLICATION_JSON);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE"})
        @DisplayName("answers other methods on the collection with 405 METHOD_NOT_ALLOWED and Allow: POST")
        void rejectsUnsupportedMethod(String method) {
            MvcTestResult result = mvc.method(HttpMethod.valueOf(method)).uri(BETS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson("bet-1", "user-42", "jackpot-lucky", "250.00")).exchange();

            assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
            assertThat(result).hasHeader(HttpHeaders.ALLOW, HttpMethod.POST.name());
            then(placementService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("answers an unconfirmed publish with 503 BET_PUBLISH_FAILED, Retry-After and the betId")
        void reportsPublishFailure() {
            givenPlacementFailsWith(new BetPublishingException("bet-1001", new TimeoutException("no ack")));

            MvcTestResult result = postBet(requestJson("bet-1001", "user-42", "jackpot-lucky", "250.00"));

            assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.BET_PUBLISH_FAILED,
                    "Bet 'bet-1001' could not be confirmed by the message broker; outcome unknown"
                            + " - retry with the same betId");
            assertRetryAfterOneSecond(result);
            assertThat(result).bodyJson().extractingPath("$.betId").isEqualTo("bet-1001");
            assertThat(result).doesNotContainHeader(HttpHeaders.LOCATION);
        }

        @Test
        @DisplayName("answers a domain invariant violation (raised by the placement service) with 400 INVALID_BET")
        void reportsInvalidBet() {
            givenPlacementFailsWith(
                    new InvalidBetException("amount must not exceed 1000000000.00 but was 1000000000.01"));

            MvcTestResult result = postBet(requestJson("bet-1001", "user-42", "jackpot-lucky", "250.00"));

            assertProblem(result, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_BET,
                    "amount must not exceed 1000000000.00 but was 1000000000.01");
            assertNoRetryAfter(result);
            assertThat(result).bodyJson().doesNotHavePath("$.errors");
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bets/{betId}")
    class GetBet {

        @ParameterizedTest
        @EnumSource(BetStatus.class)
        @DisplayName("returns the processed bet with its status")
        void returnsBet(BetStatus status) {
            given(queryService.getBet("bet-1001")).willReturn(new ProcessedBet("bet-1001", "user-42",
                    "jackpot-lucky", new BigDecimal("250.00"), status, PLACED_AT, PROCESSED_AT));

            MvcTestResult result = mvc.get().uri(BETS + "/{betId}", "bet-1001").exchange();

            assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
            assertThat(result).bodyJson().isStrictlyEqualTo("""
                    {"betId":"bet-1001","userId":"user-42","jackpotId":"jackpot-lucky","betAmount":250.00,
                     "status":"%s","placedAt":"2026-09-23T10:15:30.100001Z",
                     "processedAt":"2026-09-23T10:15:30.200002Z"}
                    """.formatted(status.name()));
            assertThat(result).bodyText().contains("\"betAmount\":250.00");
        }

        @Test
        @DisplayName("answers an unknown or not yet processed bet with 404 BET_NOT_FOUND")
        void reportsUnknownBet() {
            given(queryService.getBet("bet-404")).willThrow(new BetNotFoundException("bet-404"));

            MvcTestResult result = mvc.get().uri(BETS + "/{betId}", "bet-404").exchange();

            assertProblem(result, HttpStatus.NOT_FOUND, ErrorCode.BET_NOT_FOUND,
                    "Bet 'bet-404' was not found (unknown or not processed yet)");
            assertNoRetryAfter(result);
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bets/{betId}/contribution")
    class GetContribution {

        @Test
        @DisplayName("returns the contribution with amounts at scale 2")
        void returnsContribution() {
            given(queryService.getContribution("bet-1001")).willReturn(new Contribution("bet-1001", "user-42",
                    "jackpot-lucky", new BigDecimal("250.00"), new BigDecimal("50.00"), new BigDecimal("150.00"), 3L,
                    PROCESSED_AT));

            MvcTestResult result = mvc.get().uri(BETS + "/{betId}/contribution", "bet-1001").exchange();

            assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
            assertThat(result).bodyJson().isStrictlyEqualTo("""
                    {"betId":"bet-1001","userId":"user-42","jackpotId":"jackpot-lucky","stakeAmount":250.00,
                     "contributionAmount":50.00,"currentJackpotAmount":150.00,
                     "createdAt":"2026-09-23T10:15:30.200002Z"}
                    """);
            assertThat(result).bodyText()
                    .contains("\"stakeAmount\":250.00", "\"contributionAmount\":50.00", "\"currentJackpotAmount\":150.00");
        }
    }

    @Nested
    @DisplayName("GET /api/v1/bets/{betId}/evaluation")
    class GetEvaluation {

        @Test
        @DisplayName("returns a won evaluation with the reward and the win chance")
        void returnsWonEvaluation() {
            given(queryService.getEvaluation("bet-1001")).willReturn(new BetEvaluation("bet-1001", "user-42",
                    "jackpot-lucky", EvaluationOutcome.WON, new BigDecimal("100.0000"), new BigDecimal("150.00"), 1L,
                    PROCESSED_AT));

            MvcTestResult result = mvc.get().uri(BETS + "/{betId}/evaluation", "bet-1001").exchange();

            assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
            assertThat(result).bodyJson().isStrictlyEqualTo("""
                    {"betId":"bet-1001","userId":"user-42","jackpotId":"jackpot-lucky","outcome":"WON","won":true,
                     "rewardAmount":150.00,"winChancePercentage":100.0000,"evaluatedAt":"2026-09-23T10:15:30.200002Z"}
                    """);
            assertThat(result).bodyText().contains("\"rewardAmount\":150.00", "\"winChancePercentage\":100.0000");
        }

        @Test
        @DisplayName("returns a lost evaluation with a zero reward")
        void returnsLostEvaluation() {
            given(queryService.getEvaluation("bet-1002")).willReturn(new BetEvaluation("bet-1002", "user-7",
                    "jackpot-fixed", EvaluationOutcome.LOST, new BigDecimal("1.0000"), new BigDecimal("0.00"), 4L,
                    PROCESSED_AT));

            MvcTestResult result = mvc.get().uri(BETS + "/{betId}/evaluation", "bet-1002").exchange();

            assertThat(result).hasStatusOk();
            assertThat(result).bodyJson().isStrictlyEqualTo("""
                    {"betId":"bet-1002","userId":"user-7","jackpotId":"jackpot-fixed","outcome":"LOST","won":false,
                     "rewardAmount":0.00,"winChancePercentage":1.0000,"evaluatedAt":"2026-09-23T10:15:30.200002Z"}
                    """);
            assertThat(result).bodyText().contains("\"rewardAmount\":0.00", "\"winChancePercentage\":1.0000");
        }
    }

    @Nested
    @DisplayName("GET contribution / evaluation errors")
    class ContributionAndEvaluationErrors {

        static Stream<Arguments> lookups() {
            return Stream.of(
                    arguments(named("contribution", "/contribution"),
                            (Function<BetQueryService, Object>) service -> service.getContribution("bet-9")),
                    arguments(named("evaluation", "/evaluation"),
                            (Function<BetQueryService, Object>) service -> service.getEvaluation("bet-9")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("lookups")
        @DisplayName("answers an unknown or not yet processed bet with 404 BET_NOT_FOUND")
        void reportsUnknownBet(String suffix, Function<BetQueryService, Object> lookup) {
            given(lookup.apply(queryService)).willThrow(new BetNotFoundException("bet-9"));

            MvcTestResult result = mvc.get().uri(BETS + "/{betId}" + suffix, "bet-9").exchange();

            assertProblem(result, HttpStatus.NOT_FOUND, ErrorCode.BET_NOT_FOUND,
                    "Bet 'bet-9' was not found (unknown or not processed yet)");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("lookups")
        @DisplayName("answers a bet without matching jackpot with 422 BET_NOT_CONTRIBUTING")
        void reportsNonContributingBet(String suffix, Function<BetQueryService, Object> lookup) {
            given(lookup.apply(queryService)).willThrow(new BetNotContributingException("bet-9"));

            MvcTestResult result = mvc.get().uri(BETS + "/{betId}" + suffix, "bet-9").exchange();

            assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.BET_NOT_CONTRIBUTING,
                    "Bet 'bet-9' was processed but did not contribute to a jackpot (no matching jackpot)");
            assertNoRetryAfter(result);
        }
    }

    @Nested
    @DisplayName("content negotiation of the lookups")
    class LookupContentNegotiation {

        @ParameterizedTest(name = "GET {0}")
        @ValueSource(strings = {"/api/v1/bets/bet-1", "/api/v1/bets/bet-1/contribution",
                "/api/v1/bets/bet-1/evaluation"})
        @DisplayName("answers Accept: application/xml with 406 NOT_ACCEPTABLE without querying")
        void rejectsUnacceptableResponseType(String uri) {
            MvcTestResult result = mvc.get().uri(uri).header(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE)
                    .exchange();

            assertProblem(result, HttpStatus.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE);
            then(queryService).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("path variable validation")
    class PathVariableValidation {

        @ParameterizedTest(name = "{0} with betId ''{1}''")
        @CsvSource(delimiter = '|', value = {
                "/api/v1/bets/{betId}              | bad id!",
                "/api/v1/bets/{betId}/contribution | bet#1",
                "/api/v1/bets/{betId}/evaluation   | bet+1",
                "/api/v1/bets/{betId}              | 01234567890123456789012345678901234567890123456789012345678901234",
                "/api/v1/bets/{betId}/evaluation   | ..",
                "/api/v1/bets/{betId}/contribution | ."
        })
        @DisplayName("answers an id not matching Bet.ID_REGEX with 400 VALIDATION_FAILED on betId")
        void rejectsInvalidBetId(String uriTemplate, String betId) {
            MvcTestResult result = mvc.get().uri(uriTemplate, betId).exchange();

            assertValidationFailed(result, List.of(error("betId", ID_PATTERN_MESSAGE)));
            then(queryService).shouldHaveNoInteractions();
        }
    }
}
