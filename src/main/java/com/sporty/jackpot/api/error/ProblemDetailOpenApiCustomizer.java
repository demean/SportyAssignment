package com.sporty.jackpot.api.error;

import com.sporty.jackpot.exception.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Documents every 4xx/5xx response of the API as what {@link GlobalExceptionHandler} writes: an RFC 9457
 * {@code application/problem+json} body, schema {@value #PROBLEM_SCHEMA}, with the stable {@code code}, and the
 * {@code Retry-After} header of every 503. Without this, springdoc documents an error response with the success DTO
 * of its operation, under {@code application/json}.
 */
@Component
public class ProblemDetailOpenApiCustomizer implements OpenApiCustomizer {

    static final String PROBLEM_SCHEMA = "Problem";
    static final String FIELD_ERROR_SCHEMA = "FieldError";

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final String SERVICE_UNAVAILABLE = String.valueOf(HttpStatus.SERVICE_UNAVAILABLE.value());

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents()
                .addSchemas(FIELD_ERROR_SCHEMA, fieldErrorSchema())
                .addSchemas(PROBLEM_SCHEMA, problemSchema());
        openApi.getPaths().values().stream()
                .flatMap(path -> path.readOperations().stream())
                .flatMap(operation -> operation.getResponses().entrySet().stream())
                .filter(response -> isError(response.getKey()))
                .forEach(response -> documentProblem(response.getKey(), response.getValue()));
    }

    private static boolean isError(String statusCode) {
        return statusCode.startsWith("4") || statusCode.startsWith("5");
    }

    private static void documentProblem(String statusCode, ApiResponse response) {
        response.setContent(problemContent());
        if (SERVICE_UNAVAILABLE.equals(statusCode)) {
            Header retryAfter = new Header();
            retryAfter.setDescription("Seconds to wait before retrying");
            retryAfter.setSchema(schema("integer", "Seconds"));
            response.addHeaderObject(HttpHeaders.RETRY_AFTER, retryAfter);
        }
    }

    private static Content problemContent() {
        return new Content().addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                new io.swagger.v3.oas.models.media.MediaType().schema(reference(PROBLEM_SCHEMA)));
    }

    private static Schema<Object> problemSchema() {
        Schema<Object> code = schema("string", "Stable, machine-readable error code");
        code.setEnum(Arrays.stream(ErrorCode.values()).<Object>map(Enum::name).toList());
        Schema<Object> errors = schema("array", "VALIDATION_FAILED only: every violated constraint, sorted by field, "
                + "then message");
        errors.setItems(reference(FIELD_ERROR_SCHEMA));

        Schema<Object> problem = schema("object",
                "RFC 9457 problem details, extended with a stable error code and a timestamp");
        problem.addProperty("type", schema("string", "Problem type URI; left out for the default about:blank", "uri"));
        problem.addProperty("title", schema("string", "HTTP status phrase, e.g. Not Found"));
        problem.addProperty("status", schema("integer", "HTTP status code", "int32"));
        problem.addProperty("detail", schema("string", "Human-readable explanation"));
        problem.addProperty("instance", schema("string", "Path of the failed request", "uri-reference"));
        problem.addProperty(GlobalExceptionHandler.CODE, code);
        problem.addProperty(GlobalExceptionHandler.TIMESTAMP,
                schema("string", "When the error occurred (UTC)", "date-time"));
        problem.addProperty(GlobalExceptionHandler.ERRORS, errors);
        problem.addProperty(GlobalExceptionHandler.BET_ID,
                schema("string", "BET_PUBLISH_FAILED only: the bet whose outcome is unknown; retry with this betId"));
        problem.setRequired(List.of("title", "status", "detail", "instance", GlobalExceptionHandler.CODE,
                GlobalExceptionHandler.TIMESTAMP));
        return problem;
    }

    private static Schema<Object> fieldErrorSchema() {
        Schema<Object> fieldError = schema("object", "One violated constraint");
        fieldError.addProperty("field", schema("string", "Request field or path variable"));
        fieldError.addProperty("message", schema("string", "What is wrong with it"));
        fieldError.setRequired(List.of("field", "message"));
        return fieldError;
    }

    /** A schema of one JSON type: {@code type} is what OpenAPI 3.0 writes, {@code types} what 3.1 writes. */
    private static Schema<Object> schema(String type, String description) {
        Schema<Object> schema = new Schema<>();
        schema.setType(type);
        schema.addType(type);
        schema.setDescription(description);
        return schema;
    }

    private static Schema<Object> schema(String type, String description, String format) {
        Schema<Object> schema = schema(type, description);
        schema.setFormat(format);
        return schema;
    }

    private static Schema<Object> reference(String schemaName) {
        Schema<Object> reference = new Schema<>();
        reference.set$ref(SCHEMA_REF_PREFIX + schemaName);
        return reference;
    }
}
