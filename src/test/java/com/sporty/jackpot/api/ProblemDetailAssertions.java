package com.sporty.jackpot.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sporty.jackpot.api.error.FieldErrorDto;
import com.sporty.jackpot.exception.ErrorCode;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Assertions on the RFC 9457 problem responses of the API (DESIGN.md §8.1).
 */
public final class ProblemDetailAssertions {

    /** Detail of every {@code VALIDATION_FAILED} response. */
    public static final String VALIDATION_FAILED_DETAIL = "Request validation failed";

    private ProblemDetailAssertions() {
    }

    /**
     * Asserts the common shape of a problem response: status, {@code application/problem+json}, the standard
     * members ({@code title}, {@code status}, {@code instance} = request path; {@code type} is omitted, which RFC 9457
     * defines as {@code about:blank}) and the {@code code} and {@code timestamp} (the slice's fixed clock) properties.
     */
    public static void assertProblem(MvcTestResult result, HttpStatus status, ErrorCode code) {
        assertThat(result).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo(status.getReasonPhrase());
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(status.value());
        assertThat(result).bodyJson().extractingPath("$.instance")
                .isEqualTo(result.getRequest().getRequestURI());
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code.name());
        assertThat(result).bodyJson().extractingPath("$.timestamp")
                .isEqualTo(ApiWebMvcTestConfiguration.NOW.toString());
    }

    /**
     * Asserts a problem response including its {@code detail}.
     */
    public static void assertProblem(MvcTestResult result, HttpStatus status, ErrorCode code, String detail) {
        assertProblem(result, status, code);
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo(detail);
    }

    /**
     * Asserts a {@code 400 VALIDATION_FAILED} response whose {@code errors} are exactly (and in this order) the
     * given ones.
     */
    public static void assertValidationFailed(MvcTestResult result, List<FieldErrorDto> expectedErrors) {
        assertProblem(result, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, VALIDATION_FAILED_DETAIL);
        assertThat(result).bodyJson().extractingPath("$.errors")
                .convertTo(InstanceOfAssertFactories.list(FieldErrorDto.class))
                .containsExactlyElementsOf(expectedErrors);
    }

    /**
     * Asserts that a (non-503) problem response carries no {@code Retry-After} header.
     */
    public static void assertNoRetryAfter(MvcTestResult result) {
        assertThat(result).doesNotContainHeader(HttpHeaders.RETRY_AFTER);
    }

    /**
     * Asserts the {@code Retry-After: 1} header of the retryable 503 responses.
     */
    public static void assertRetryAfterOneSecond(MvcTestResult result) {
        assertThat(result).hasHeader(HttpHeaders.RETRY_AFTER, "1");
    }
}
