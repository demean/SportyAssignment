package com.sporty.jackpot.api;

import com.sporty.jackpot.api.mapper.ApiMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * Beans the {@code @WebMvcTest} slices of the API need but do not scan: the real {@link ApiMapper} and a fixed
 * clock, so the {@code timestamp} of problem responses is deterministic.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(ApiMapper.class)
public class ApiWebMvcTestConfiguration {

    /** The instant of the slice's clock (microsecond precision); the tests also accept their bets at it. */
    public static final Instant NOW = Instant.parse("2026-09-23T10:15:30.123456Z");

    @Bean
    Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
