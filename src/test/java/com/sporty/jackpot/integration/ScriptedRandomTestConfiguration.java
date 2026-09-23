package com.sporty.jackpot.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Makes reward draws deterministic in the contexts that import it: a {@link ScriptedRandomGenerator} takes precedence
 * over the application's {@code SecureRandom}. Being a {@code @TestConfiguration}, it is never picked up by component
 * scanning, so the shared Embedded-Kafka context keeps the real random source.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedRandomTestConfiguration {

    @Bean
    @Primary
    ScriptedRandomGenerator scriptedRandomGenerator() {
        return new ScriptedRandomGenerator();
    }
}
