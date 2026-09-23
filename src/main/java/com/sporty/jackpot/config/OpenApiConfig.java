package com.sporty.jackpot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata (served at {@code /v3/api-docs}, Swagger UI at {@code /swagger-ui.html}).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    OpenAPI jackpotOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Jackpot Service API")
                .version("v1")
                .description("Publish bets to Kafka and query their jackpot contributions and reward evaluations. "
                        + "Bets are processed asynchronously: POST returns 202 and the result becomes available "
                        + "under /api/v1/bets/{betId} once the bet has been consumed."));
    }
}
