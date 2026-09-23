package com.sporty.jackpot.api.controller;

import static com.sporty.jackpot.api.ProblemDetailAssertions.assertNoRetryAfter;
import static com.sporty.jackpot.api.ProblemDetailAssertions.assertProblem;
import static com.sporty.jackpot.api.ProblemDetailAssertions.assertValidationFailed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.sporty.jackpot.api.ApiWebMvcTestConfiguration;
import com.sporty.jackpot.api.error.FieldErrorDto;
import com.sporty.jackpot.domain.model.Bet;
import com.sporty.jackpot.domain.model.Jackpot;
import com.sporty.jackpot.domain.policy.FixedChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.FixedContributionPolicy;
import com.sporty.jackpot.domain.policy.VariableChanceRewardPolicy;
import com.sporty.jackpot.domain.policy.VariableContributionPolicy;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotNotFoundException;
import com.sporty.jackpot.service.JackpotQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@WebMvcTest(JackpotController.class)
@Import(ApiWebMvcTestConfiguration.class)
@DisplayName("JackpotController (web slice)")
class JackpotControllerTest {

    private static final String JACKPOTS = "/api/v1/jackpots";
    private static final Instant UPDATED_AT = Instant.parse("2026-09-23T09:00:00.000001Z");

    /** Fixed contribution + fixed chance, as seeded for {@code jackpot-fixed}. */
    private static final Jackpot FIXED = new Jackpot("jackpot-fixed", "Fixed Classic", new BigDecimal("1000.00"),
            new BigDecimal("1005.00"), 1L, new FixedContributionPolicy(new BigDecimal("5.0")),
            new FixedChanceRewardPolicy(new BigDecimal("1.0")), UPDATED_AT);

    /** Variable contribution + variable chance, as seeded for {@code jackpot-lucky}. */
    private static final Jackpot LUCKY = new Jackpot("jackpot-lucky", "Lucky Demo", new BigDecimal("100.00"),
            new BigDecimal("100.00"), 2L,
            new VariableContributionPolicy(new BigDecimal("20.0"), new BigDecimal("5.0"), new BigDecimal("1.0"),
                    new BigDecimal("100")),
            new VariableChanceRewardPolicy(new BigDecimal("5.0"), new BigDecimal("10.0"), new BigDecimal("10"),
                    new BigDecimal("150")),
            UPDATED_AT);

    /** Fixed contribution + variable chance, as seeded for {@code jackpot-mixed}. */
    private static final Jackpot MIXED = new Jackpot("jackpot-mixed", "Mixed Mega", new BigDecimal("10000.00"),
            new BigDecimal("12345.67"), 7L, new FixedContributionPolicy(new BigDecimal("2.0")),
            new VariableChanceRewardPolicy(new BigDecimal("0.01"), new BigDecimal("0.1"), new BigDecimal("5000"),
                    new BigDecimal("100000")),
            UPDATED_AT);

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private JackpotQueryService queryService;

    @Test
    @DisplayName("GET /jackpots lists the jackpots in service order with their policies")
    void listsJackpots() {
        given(queryService.findAll()).willReturn(List.of(FIXED, LUCKY));

        MvcTestResult result = mvc.get().uri(JACKPOTS).exchange();

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                [
                  {"id":"jackpot-fixed","name":"Fixed Classic","initialPoolAmount":1000.00,
                   "currentPoolAmount":1005.00,"cycle":1,
                   "contributionPolicy":{"type":"FIXED","parameters":{"percentage":5.0}},
                   "rewardPolicy":{"type":"FIXED","parameters":{"chancePercentage":1.0}},
                   "updatedAt":"2026-09-23T09:00:00.000001Z"},
                  {"id":"jackpot-lucky","name":"Lucky Demo","initialPoolAmount":100.00,
                   "currentPoolAmount":100.00,"cycle":2,
                   "contributionPolicy":{"type":"VARIABLE","parameters":{"startPercentage":20.0,"minPercentage":5.0,
                                         "decayPercentage":1.0,"poolIncreaseStep":100}},
                   "rewardPolicy":{"type":"VARIABLE","parameters":{"startChancePercentage":5.0,
                                   "chanceIncreasePercentage":10.0,"poolIncreaseStep":10,"poolLimit":150}},
                   "updatedAt":"2026-09-23T09:00:00.000001Z"}
                ]
                """);
    }

    @Test
    @DisplayName("GET /jackpots serializes policy parameters in their deterministic order and amounts at scale 2")
    void keepsParameterOrderAndScale() {
        given(queryService.findAll()).willReturn(List.of(LUCKY));

        MvcTestResult result = mvc.get().uri(JACKPOTS).exchange();

        assertThat(result).bodyText().contains(
                "\"initialPoolAmount\":100.00",
                "\"parameters\":{\"startPercentage\":20.0,\"minPercentage\":5.0,\"decayPercentage\":1.0,"
                        + "\"poolIncreaseStep\":100}",
                "\"parameters\":{\"startChancePercentage\":5.0,\"chanceIncreasePercentage\":10.0,"
                        + "\"poolIncreaseStep\":10,\"poolLimit\":150}");
    }

    @Test
    @DisplayName("GET /jackpots returns an empty array when there are no jackpots")
    void listsNoJackpots() {
        given(queryService.findAll()).willReturn(List.of());

        MvcTestResult result = mvc.get().uri(JACKPOTS).exchange();

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().isStrictlyEqualTo("[]");
    }

    @Test
    @DisplayName("GET /jackpots/{id} returns one jackpot")
    void returnsJackpot() {
        given(queryService.getJackpot("jackpot-mixed")).willReturn(MIXED);

        MvcTestResult result = mvc.get().uri(JACKPOTS + "/{jackpotId}", "jackpot-mixed").exchange();

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"id":"jackpot-mixed","name":"Mixed Mega","initialPoolAmount":10000.00,
                 "currentPoolAmount":12345.67,"cycle":7,
                 "contributionPolicy":{"type":"FIXED","parameters":{"percentage":2.0}},
                 "rewardPolicy":{"type":"VARIABLE","parameters":{"startChancePercentage":0.01,
                                 "chanceIncreasePercentage":0.1,"poolIncreaseStep":5000,"poolLimit":100000}},
                 "updatedAt":"2026-09-23T09:00:00.000001Z"}
                """);
        assertThat(result).bodyText().contains("\"initialPoolAmount\":10000.00", "\"currentPoolAmount\":12345.67");
    }

    @Test
    @DisplayName("GET /jackpots/{id} answers an unknown jackpot with 404 JACKPOT_NOT_FOUND")
    void reportsUnknownJackpot() {
        given(queryService.getJackpot("jackpot-nope")).willThrow(new JackpotNotFoundException("jackpot-nope"));

        MvcTestResult result = mvc.get().uri(JACKPOTS + "/{jackpotId}", "jackpot-nope").exchange();

        assertProblem(result, HttpStatus.NOT_FOUND, ErrorCode.JACKPOT_NOT_FOUND, "Jackpot 'jackpot-nope' was not found");
        assertNoRetryAfter(result);
    }

    @ParameterizedTest(name = "jackpotId ''{0}''")
    @ValueSource(strings = {"jackpot 1", "jackpot#1", "jäckpot",
            "01234567890123456789012345678901234567890123456789012345678901234"})
    @DisplayName("GET /jackpots/{id} answers an id not matching Bet.ID_REGEX with 400 VALIDATION_FAILED")
    void rejectsInvalidJackpotId(String jackpotId) {
        MvcTestResult result = mvc.get().uri(JACKPOTS + "/{jackpotId}", jackpotId).exchange();

        assertValidationFailed(result, List.of(new FieldErrorDto("jackpotId", "must match \"" + Bet.ID_REGEX + "\"")));
        then(queryService).shouldHaveNoInteractions();
    }

    @ParameterizedTest(name = "Accept: {0}")
    @ValueSource(strings = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    @DisplayName("answers a non-JSON Accept header with 406 NOT_ACCEPTABLE (problem+json) without querying")
    void rejectsUnacceptableMediaType(String accept) {
        MvcTestResult list = mvc.get().uri(JACKPOTS).header(HttpHeaders.ACCEPT, accept).exchange();
        MvcTestResult single = mvc.get().uri(JACKPOTS + "/{jackpotId}", "jackpot-fixed")
                .header(HttpHeaders.ACCEPT, accept).exchange();

        assertProblem(list, HttpStatus.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE);
        assertProblem(single, HttpStatus.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE);
        assertNoRetryAfter(list);
        then(queryService).shouldHaveNoInteractions();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("answers write methods with 405 METHOD_NOT_ALLOWED and Allow: GET")
    void rejectsUnsupportedMethod(String method) {
        MvcTestResult result = mvc.method(HttpMethod.valueOf(method)).uri(JACKPOTS + "/{jackpotId}", "jackpot-fixed")
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();

        assertProblem(result, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
        assertThat(result).headers().hasHeaderSatisfying(HttpHeaders.ALLOW,
                values -> assertThat(String.join(",", values)).contains(HttpMethod.GET.name())
                        .doesNotContain(method));
        then(queryService).shouldHaveNoInteractions();
    }
}
