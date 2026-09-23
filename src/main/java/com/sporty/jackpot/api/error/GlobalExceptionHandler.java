package com.sporty.jackpot.api.error;

import com.sporty.jackpot.exception.BetPublishingException;
import com.sporty.jackpot.exception.ErrorCode;
import com.sporty.jackpot.exception.JackpotConfigurationException;
import com.sporty.jackpot.exception.JackpotServiceException;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to an RFC 9457 {@link ProblemDetail} carrying a stable {@code code} and a {@code timestamp}.
 * Unexpected failures are logged at ERROR and answered with a generic detail (internals never leak).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";
    static final String TIMESTAMP = "timestamp";
    static final String ERRORS = "errors";
    static final String BET_ID = "betId";
    static final String RETRY_AFTER_SECONDS = "1";
    static final String VALIDATION_FAILED_DETAIL = "Request validation failed";
    static final String INTERNAL_ERROR_DETAIL = "An unexpected error occurred";
    static final String TEMPORARILY_UNAVAILABLE_DETAIL = "The service is temporarily unavailable, please retry";
    static final String CONFLICT_DETAIL = "The request conflicts with the current state of the resource";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Comparator<FieldErrorDto> FIELD_ERROR_ORDER =
            Comparator.comparing(FieldErrorDto::field).thenComparing(FieldErrorDto::message);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(BetPublishingException.class)
    ResponseEntity<Object> handleBetPublishing(BetPublishingException ex) {
        ProblemDetail problem = problem(ErrorCode.BET_PUBLISH_FAILED, ex.getMessage());
        problem.setProperty(BET_ID, ex.getBetId());
        return respond(problem);
    }

    @ExceptionHandler(JackpotConfigurationException.class)
    ResponseEntity<Object> handleJackpotConfiguration(JackpotConfigurationException ex) {
        return handleUnexpected(ex);
    }

    @ExceptionHandler(JackpotServiceException.class)
    ResponseEntity<Object> handleJackpotService(JackpotServiceException ex) {
        return respond(problem(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler({ConcurrencyFailureException.class, TransientDataAccessException.class,
            CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    ResponseEntity<Object> handleTemporarilyUnavailable(Exception ex) {
        log.warn("Request failed with a transient data access error: {}", ex.toString());
        return respond(problem(ErrorCode.TEMPORARILY_UNAVAILABLE, TEMPORARILY_UNAVAILABLE_DETAIL));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Request failed with a data integrity violation: {}", ex.toString());
        return respond(problem(ErrorCode.CONFLICT, CONFLICT_DETAIL));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error while handling a request", ex);
        return respond(problem(ErrorCode.INTERNAL_ERROR, INTERNAL_ERROR_DETAIL));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return validationFailed(ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDto(error.getField(), String.valueOf(error.getDefaultMessage())))
                .toList());
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        return validationFailed(ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldErrorDto(String.valueOf(result.getMethodParameter().getParameterName()),
                                String.valueOf(error.getDefaultMessage()))))
                .toList());
    }

    /**
     * Final step of every exception handled by the base class: the body is always the {@link ProblemDetail} the
     * base class resolved; adds {@code code} (derived from the status) and {@code timestamp}.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        ProblemDetail problem = (ProblemDetail) body;
        problem.setProperty(CODE, codeFor(statusCode).name());
        problem.setProperty(TIMESTAMP, clock.instant());
        return super.createResponseEntity(problem, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationFailed(List<FieldErrorDto> errors) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, VALIDATION_FAILED_DETAIL);
        problem.setProperty(ERRORS, errors.stream().sorted(FIELD_ERROR_ORDER).toList());
        return respond(problem);
    }

    private ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ErrorHttpStatus.of(code), detail);
        problem.setProperty(CODE, code.name());
        problem.setProperty(TIMESTAMP, clock.instant());
        return problem;
    }

    private static ResponseEntity<Object> respond(ProblemDetail problem) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.getStatus());
        if (problem.getStatus() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response.body(problem);
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? ErrorCode.MALFORMED_REQUEST : ErrorCode.INTERNAL_ERROR;
        };
    }
}
