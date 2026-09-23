package com.sporty.jackpot.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProblemDetailOpenApiCustomizer")
class ProblemDetailOpenApiCustomizerTest {

    private final ProblemDetailOpenApiCustomizer customizer = new ProblemDetailOpenApiCustomizer();

    private static ApiResponse jsonResponse(String schemaRef) {
        return new ApiResponse().description("response").content(new Content().addMediaType("application/json",
                new MediaType().schema(new Schema<>().$ref(schemaRef))));
    }

    private static OpenAPI apiWith(ApiResponses responses) {
        return new OpenAPI().paths(new Paths().addPathItem("/things/{id}",
                new PathItem().get(new Operation().responses(responses))));
    }

    @Test
    @DisplayName("replaces the content of 4xx/5xx responses with problem+json and keeps success responses")
    void documentsErrorResponsesAsProblems() {
        ApiResponses responses = new ApiResponses()
                .addApiResponse("200", jsonResponse("#/components/schemas/Thing"))
                .addApiResponse("404", jsonResponse("#/components/schemas/Thing"))
                .addApiResponse("503", jsonResponse("#/components/schemas/Thing"));
        OpenAPI openApi = apiWith(responses);
        openApi.setComponents(new Components().addSchemas("Thing", new Schema<>()));

        customizer.customise(openApi);

        assertThat(responses.get("200").getContent()).containsOnlyKeys("application/json");
        assertThat(responses.get("200").getHeaders()).isNull();
        for (String status : new String[] {"404", "503"}) {
            assertThat(responses.get(status).getContent()).containsOnlyKeys("application/problem+json");
            assertThat(responses.get(status).getContent().get("application/problem+json").getSchema().get$ref())
                    .isEqualTo("#/components/schemas/Problem");
            assertThat(responses.get(status).getDescription()).as("the documented error codes stay")
                    .isEqualTo("response");
        }
        assertThat(responses.get("404").getHeaders()).isNull();
        assertThat(responses.get("503").getHeaders()).containsOnlyKeys("Retry-After");
        assertThat(openApi.getComponents().getSchemas()).containsOnlyKeys("Thing", "Problem", "FieldError");
    }

    @Test
    @DisplayName("registers the Problem schema even when the document has no components yet")
    void createsTheComponents() {
        OpenAPI openApi = apiWith(new ApiResponses().addApiResponse("400", jsonResponse("#/x")));

        customizer.customise(openApi);

        Schema<?> problem = openApi.getComponents().getSchemas().get("Problem");
        assertThat(problem.getProperties()).containsKeys("type", "title", "status", "detail", "instance", "code",
                "timestamp", "errors", "betId");
        assertThat(problem.getTypes()).containsExactly("object");
        assertThat(problem.getProperties().get("code").getEnum()).contains("VALIDATION_FAILED", "BET_NOT_FOUND");
        assertThat(openApi.getComponents().getSchemas().get("FieldError").getRequired())
                .containsExactly("field", "message");
    }
}
