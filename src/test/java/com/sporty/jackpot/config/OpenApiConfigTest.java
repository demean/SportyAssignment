package com.sporty.jackpot.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("OpenApiConfig")
class OpenApiConfigTest {

    @Test
    @DisplayName("publishes the API metadata, including the asynchronous processing contract")
    void apiMetadata() {
        new ApplicationContextRunner().withUserConfiguration(OpenApiConfig.class).run(context -> {
            assertThat(context).hasSingleBean(OpenAPI.class);
            Info info = context.getBean(OpenAPI.class).getInfo();

            assertThat(info.getTitle()).isEqualTo("Jackpot Service API");
            assertThat(info.getVersion()).isEqualTo("v1");
            assertThat(info.getDescription())
                    .contains("POST returns 202")
                    .contains("/api/v1/bets/{betId}");
        });
    }
}
