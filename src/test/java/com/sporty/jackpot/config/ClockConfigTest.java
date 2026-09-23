package com.sporty.jackpot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("ClockConfig")
class ClockConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class);

    @Test
    @DisplayName("provides a UTC clock close to the system time")
    void utcSystemClock() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            Clock clock = context.getBean(Clock.class);

            assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(clock.instant()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        });
    }

    @Test
    @DisplayName("ticks in whole microseconds, so instants survive a TIMESTAMP WITH TIME ZONE round trip unchanged")
    void microsecondTicks() {
        runner.run(context -> {
            Clock clock = context.getBean(Clock.class);

            assertThat(IntStream.range(0, 1_000).mapToObj(i -> clock.instant()))
                    .allSatisfy(instant -> assertThat(instant.truncatedTo(ChronoUnit.MICROS)).isEqualTo(instant));
        });
    }
}
