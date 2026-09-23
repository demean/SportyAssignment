package com.sporty.jackpot.config;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * UTC clock ticking in microseconds, so persisted ({@code TIMESTAMP WITH TIME ZONE}) and returned instants are
 * identical.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.of(1, ChronoUnit.MICROS));
    }
}
