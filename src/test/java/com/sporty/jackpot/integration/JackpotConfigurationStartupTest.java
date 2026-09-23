package com.sporty.jackpot.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sporty.jackpot.JackpotServiceApplication;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * The real application, started the way {@code main} starts it (T8, T22): a jackpot whose policies contradict its
 * initial pool stops the startup before the context refresh completes, i.e. before any lifecycle bean (the Kafka
 * listener containers, the web server) is started. Also proves the single-instance WARN of the in-memory H2 database.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Jackpot configuration check at startup (T8, T22)")
class JackpotConfigurationStartupTest {

    private static SpringApplication application(List<ApplicationEvent> events) {
        return new SpringApplicationBuilder(JackpotServiceApplication.class)
                .web(WebApplicationType.NONE)
                .listeners((ApplicationListener<ApplicationEvent>) events::add)
                .build();
    }

    @Test
    @DisplayName("a pool limit not above the initial pool fails the startup before any consumer or request is served")
    void misconfiguredJackpotFailsTheStartup(CapturedOutput output) {
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();

        // jackpot-variable's variable chance reaches 100 % at a pool of 25000: an initial pool of 30000.00 would
        // make every contributing bet win the whole pool
        assertThatThrownBy(() -> application(events).run("--JACKPOT_VARIABLE_INITIAL_POOL=30000.00").close())
                .hasStackTraceContaining("Jackpot 'jackpot-variable' is misconfigured: poolLimit (25000) must be "
                        + "greater than the initial pool (30000.00)");

        assertThat(events).as("the refresh never completed: no lifecycle bean was started")
                .noneMatch(ContextRefreshedEvent.class::isInstance)
                .noneMatch(ApplicationStartedEvent.class::isInstance)
                .anyMatch(ApplicationFailedEvent.class::isInstance);
        assertThat(output).contains("Running on the in-memory H2 database: data is volatile and only a single "
                + "instance is supported");
    }

    @Test
    @DisplayName("with the seeded configuration the application starts and validates every jackpot")
    void seededConfigurationStarts(CapturedOutput output) {
        List<ApplicationEvent> events = new CopyOnWriteArrayList<>();

        try (ConfigurableApplicationContext context = application(events).run()) {
            assertThat(context.isRunning()).isTrue();
        }

        assertThat(events).anyMatch(ApplicationStartedEvent.class::isInstance);
        assertThat(output).contains("Validated the configuration of 4 jackpot(s)");
    }
}
